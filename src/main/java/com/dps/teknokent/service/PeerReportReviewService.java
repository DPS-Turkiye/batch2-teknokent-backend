package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.PeerReportKind;
import com.dps.teknokent.domain.PeerReportStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.PeerReviewQueueItemResponse;
import com.dps.teknokent.dto.responses.PeerReviewedReportResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.PeerDailyReport;
import com.dps.teknokent.entity.PeerMeetingReport;
import com.dps.teknokent.entity.PeerReportReview;
import com.dps.teknokent.entity.PeerWeeklyReport;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.PeerDailyReportRepository;
import com.dps.teknokent.repository.PeerMeetingReportRepository;
import com.dps.teknokent.repository.PeerWeeklyReportRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * The coordinator's side of the peer-mentor reports: the sent reports of the peer programs they run (an admin: all),
 * read in full and approved or sent back for revision. A mentor's draft is theirs alone, so it is never listed or
 * shown here; a report outside the reviewer's programs is not found.
 */
@Service
@RequiredArgsConstructor
public class PeerReportReviewService {

	/** What a reviewer sees: everything the mentor has sent, never a draft. */
	private static final Set<PeerReportStatus> SENT = EnumSet.of(PeerReportStatus.IN_REVIEW,
			PeerReportStatus.APPROVED, PeerReportStatus.REVISION_REQUESTED);

	private final DomainLookupService domainLookupService;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final AppUserRepository userRepository;
	private final MeetingRepository meetingRepository;
	private final PeerMeetingReportRepository meetingReportRepository;
	private final PeerDailyReportRepository dailyReportRepository;
	private final PeerWeeklyReportRepository weeklyReportRepository;
	private final PeerMeetingReportService meetingReports;
	private final PeerDailyReportService dailyReports;
	private final PeerWeeklyReportService weeklyReports;
	private final PeerReportCalendar calendar;

	/**
	 * The reviewer's list, oldest sent first: by default what waits for them (IN_REVIEW). {@code programId} narrows
	 * it to one of their programs; a program they don't run gives an empty list, never someone else's reports.
	 */
	@Transactional(readOnly = true)
	public List<PeerReviewQueueItemResponse> queue(UUID actorId, UUID programId, PeerReportKind kind,
			PeerReportStatus status) {
		Reviewer reviewer = requireReviewer(actorId);
		Set<PeerReportStatus> statuses = status == null ? EnumSet.of(PeerReportStatus.IN_REVIEW)
				: SENT.contains(status) ? EnumSet.of(status) : EnumSet.noneOf(PeerReportStatus.class);
		if (statuses.isEmpty() || (programId != null && !reviewer.mayReview(programId))) {
			return List.of();
		}
		Set<UUID> programs = programId != null ? Set.of(programId) : reviewer.programIds();
		List<PeerReviewQueueItemResponse> items = new ArrayList<>();
		if (kind == null || kind == PeerReportKind.MEETING) {
			items.addAll(meetingItems(reviewer.admin() && programId == null
					? meetingReportRepository.findByStatusInOrderBySubmittedAtAsc(statuses)
					: meetingReportRepository.findByProgramIdInAndStatusInOrderBySubmittedAtAsc(programs, statuses)));
		}
		if (kind == null || kind == PeerReportKind.DAILY) {
			items.addAll(simpleItems(PeerReportKind.DAILY, reviewer.admin() && programId == null
					? dailyReportRepository.findByStatusInOrderBySubmittedAtAsc(statuses)
					: dailyReportRepository.findByProgramIdInAndStatusInOrderBySubmittedAtAsc(programs, statuses),
					PeerDailyReport::getId, PeerDailyReport::getProgramId, PeerDailyReport::getMentorId,
					PeerDailyReport::getReportDate));
		}
		if (kind == null || kind == PeerReportKind.WEEKLY) {
			items.addAll(simpleItems(PeerReportKind.WEEKLY, reviewer.admin() && programId == null
					? weeklyReportRepository.findByStatusInOrderBySubmittedAtAsc(statuses)
					: weeklyReportRepository.findByProgramIdInAndStatusInOrderBySubmittedAtAsc(programs, statuses),
					PeerWeeklyReport::getId, PeerWeeklyReport::getProgramId, PeerWeeklyReport::getMentorId,
					PeerWeeklyReport::getWeekStart));
		}
		items.sort(Comparator.comparing(PeerReviewQueueItemResponse::submittedAt,
				Comparator.nullsLast(Comparator.naturalOrder())));
		return items;
	}

	@Transactional(readOnly = true)
	public PeerReviewedReportResponse meetingReport(UUID actorId, UUID reportId) {
		PeerMeetingReport report = reviewableMeetingReport(requireReviewer(actorId), reportId);
		return meetingResponse(report);
	}

	@Transactional(readOnly = true)
	public PeerReviewedReportResponse dailyReport(UUID actorId, UUID reportId) {
		PeerDailyReport report = reviewable(requireReviewer(actorId),
				dailyReportRepository.findById(reportId).orElse(null), reportId);
		return dailyResponse(report);
	}

	@Transactional(readOnly = true)
	public PeerReviewedReportResponse weeklyReport(UUID actorId, UUID reportId) {
		PeerWeeklyReport report = reviewable(requireReviewer(actorId),
				weeklyReportRepository.findById(reportId).orElse(null), reportId);
		return weeklyResponse(report);
	}

	/** Approves a report waiting for review; any other state is a conflict, not a silent no-op. */
	@Transactional
	public PeerReviewedReportResponse approve(UUID actorId, PeerReportKind kind, UUID reportId) {
		return decide(actorId, kind, reportId, PeerReportStatus.APPROVED, null);
	}

	/** Sends a report back to its mentor with a note; it reopens for editing ("Revizyon Gerekli"). */
	@Transactional
	public PeerReviewedReportResponse requestRevision(UUID actorId, PeerReportKind kind, UUID reportId,
			String note) {
		return decide(actorId, kind, reportId, PeerReportStatus.REVISION_REQUESTED, note.trim());
	}

	private PeerReviewedReportResponse decide(UUID actorId, PeerReportKind kind, UUID reportId,
			PeerReportStatus outcome, String note) {
		Reviewer reviewer = requireReviewer(actorId);
		return switch (kind) {
			case MEETING -> {
				PeerMeetingReport report = reviewableMeetingReport(reviewer, reportId);
				record(report, reviewer, outcome, note);
				yield meetingResponse(PeerReportAccess.savedOrConflict(() -> meetingReportRepository.saveAndFlush(report)));
			}
			case DAILY -> {
				PeerDailyReport report = reviewable(reviewer, dailyReportRepository.findById(reportId).orElse(null),
						reportId);
				record(report, reviewer, outcome, note);
				yield dailyResponse(PeerReportAccess.savedOrConflict(() -> dailyReportRepository.saveAndFlush(report)));
			}
			case WEEKLY -> {
				PeerWeeklyReport report = reviewable(reviewer, weeklyReportRepository.findById(reportId).orElse(null),
						reportId);
				record(report, reviewer, outcome, note);
				yield weeklyResponse(
						PeerReportAccess.savedOrConflict(() -> weeklyReportRepository.saveAndFlush(report)));
			}
		};
	}

	private void record(PeerReportReview report, Reviewer reviewer, PeerReportStatus outcome, String note) {
		if (report.getStatus() != PeerReportStatus.IN_REVIEW) {
			throw new ConflictException(Texts.forRequest("error.peerReportNotInReview"));
		}
		Instant now = calendar.now();
		report.setStatus(outcome);
		report.setReviewedAt(now);
		report.setReviewedBy(reviewer.user().getId());
		report.setUpdatedAt(now);
		if (note != null) {
			report.setRevisionNote(note);
		}
	}

	private PeerMeetingReport reviewableMeetingReport(Reviewer reviewer, UUID reportId) {
		return reviewable(reviewer, meetingReportRepository.findById(reportId).orElse(null), reportId);
	}

	/** A sent report of a program the reviewer runs; a draft, a stranger's report or a missing id is not found. */
	private <R extends PeerReportReview> R reviewable(Reviewer reviewer, R report, UUID reportId) {
		if (report == null || !SENT.contains(report.getStatus()) || !reviewer.mayReview(programOf(report))) {
			throw new ResourceNotFoundException("Peer report not found: " + reportId);
		}
		return report;
	}

	private static UUID programOf(PeerReportReview report) {
		return switch (report) {
			case PeerMeetingReport meeting -> meeting.getProgramId();
			case PeerDailyReport daily -> daily.getProgramId();
			case PeerWeeklyReport weekly -> weekly.getProgramId();
			default -> null;
		};
	}

	private PeerReviewedReportResponse meetingResponse(PeerMeetingReport report) {
		AppUser mentor = domainLookupService.requireUser(report.getMentorId());
		Meeting meeting = meetingRepository.findById(report.getMeetingId())
				.orElseThrow(() -> new ResourceNotFoundException("Peer report not found: " + report.getId()));
		return new PeerReviewedReportResponse(PeerReportKind.MEETING, report.getId(), report.getProgramId(),
				mentorOf(mentor), meetingReports.response(mentor, meeting, report), null, null);
	}

	private PeerReviewedReportResponse dailyResponse(PeerDailyReport report) {
		AppUser mentor = domainLookupService.requireUser(report.getMentorId());
		return new PeerReviewedReportResponse(PeerReportKind.DAILY, report.getId(), report.getProgramId(),
				mentorOf(mentor), null, dailyReports.response(mentor, report.getReportDate(), report), null);
	}

	private PeerReviewedReportResponse weeklyResponse(PeerWeeklyReport report) {
		AppUser mentor = domainLookupService.requireUser(report.getMentorId());
		return new PeerReviewedReportResponse(PeerReportKind.WEEKLY, report.getId(), report.getProgramId(),
				mentorOf(mentor), null, null, weeklyReports.response(mentor, report.getWeekStart(), report));
	}

	private static PeerReviewedReportResponse.Mentor mentorOf(AppUser mentor) {
		return new PeerReviewedReportResponse.Mentor(mentor.getId(), mentor.getFullName());
	}

	private List<PeerReviewQueueItemResponse> meetingItems(List<PeerMeetingReport> reports) {
		Set<UUID> people = new HashSet<>();
		reports.forEach(report -> {
			people.add(report.getMentorId());
			people.add(report.getMenteeId());
		});
		Map<UUID, String> names = names(people);
		Map<UUID, LocalDate> days = meetingRepository
				.findAllById(reports.stream().map(PeerMeetingReport::getMeetingId).toList()).stream()
				.collect(Collectors.toMap(Meeting::getId, meeting -> calendar.dateOf(meeting.getScheduledAt())));
		return reports.stream()
				.map(report -> new PeerReviewQueueItemResponse(PeerReportKind.MEETING, report.getId(),
						report.getProgramId(), report.getMentorId(), names.get(report.getMentorId()),
						days.get(report.getMeetingId()), report.getMenteeId(), names.get(report.getMenteeId()),
						report.getCoordinatorSupportNeeded(), report.getStatus(), report.getSubmittedAt()))
				.toList();
	}

	private <R extends PeerReportReview> List<PeerReviewQueueItemResponse> simpleItems(PeerReportKind kind,
			List<R> reports, Function<R, UUID> id, Function<R, UUID> program, Function<R, UUID> mentor,
			Function<R, LocalDate> day) {
		Map<UUID, String> names = names(reports.stream().map(mentor).collect(Collectors.toSet()));
		return reports.stream()
				.map(report -> new PeerReviewQueueItemResponse(kind, id.apply(report), program.apply(report),
						mentor.apply(report), names.get(mentor.apply(report)), day.apply(report), null, null, null,
						report.getStatus(), report.getSubmittedAt()))
				.toList();
	}

	private Map<UUID, String> names(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return userRepository.findAllById(ids).stream()
				.collect(Collectors.toMap(AppUser::getId, user -> Objects.requireNonNullElse(user.getFullName(), "")));
	}

	/** An admin, or a coordinator who runs at least one peer program; anyone else is refused. */
	private Reviewer requireReviewer(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.has(UserRole.ADMIN)) {
			return new Reviewer(actor, true, Set.of());
		}
		Set<UUID> programs = ProgramCoordination.programsOf(actor.getId(), programRepository,
				programCoordinatorRepository).stream()
				.filter(program -> program.getProgramType() == ProgramType.PEER_MENTORING)
				.map(Program::getId)
				.collect(Collectors.toSet());
		if (programs.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.peerReportReviewersOnly"));
		}
		return new Reviewer(actor, false, programs);
	}

	private record Reviewer(AppUser user, boolean admin, Set<UUID> programIds) {

		/** A report without a program (its mentor had none running) is the admins' alone. */
		boolean mayReview(UUID programId) {
			return admin || (programId != null && programIds.contains(programId));
		}
	}
}
