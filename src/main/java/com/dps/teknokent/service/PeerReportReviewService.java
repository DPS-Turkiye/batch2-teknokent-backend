package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.PeerReportKind;
import com.dps.teknokent.domain.PeerReportStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
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
 * The coordinator's side of the peer-mentor reports (Arya, 14 Nov): only the weekly summary is reviewed. Its approval
 * covers the week's daily and meeting reports; a summary nobody decided on within seven days counts as approved
 * ({@link PeerReportStatuses}). Coordinators of a peer program (and admins) read its sent reports, never a draft and
 * never their own; a report outside the reviewer's programs is not found.
 */
@Service
@RequiredArgsConstructor
public class PeerReportReviewService {

	/** What a reviewer may open: sent and not back with its mentor. */
	private static final Set<PeerReportStatus> READABLE = EnumSet.of(PeerReportStatus.IN_REVIEW,
			PeerReportStatus.APPROVED);

	/** What a weekly summary can be stored as once sent (a list's candidates). */
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
	private final PeerReportStatuses statuses;
	private final PeerReportAccess access;

	/**
	 * The reviewer's list of weekly summaries, oldest sent first: by default those waiting for them (in review and not
	 * yet past the seven days). {@code status} filters by the status they show. {@code programId} narrows it to one of
	 * their programs; one they don't run gives an empty list, never someone else's reports.
	 */
	@Transactional(readOnly = true)
	public List<PeerReviewQueueItemResponse> queue(UUID actorId, UUID programId, PeerReportStatus status) {
		Reviewer reviewer = requireReviewer(actorId);
		PeerReportStatus shown = status == null ? PeerReportStatus.IN_REVIEW : status;
		if (!SENT.contains(shown) || (programId != null && !reviewer.mayReview(programId))) {
			return List.of();
		}
		List<PeerWeeklyReport> weeks = reviewer.admin() && programId == null
				? weeklyReportRepository.findByStatusInOrderBySubmittedAtAsc(SENT)
				: weeklyReportRepository.findByProgramIdInAndStatusInOrderBySubmittedAtAsc(
						programId != null ? Set.of(programId) : reviewer.programIds(), SENT);
		List<PeerWeeklyReport> listed = weeks.stream()
				.filter(week -> statuses.ofWeek(week) == shown)
				// Nobody reviews their own reports, even when they also coordinate the program they mentor in.
				.filter(week -> !week.getMentorId().equals(reviewer.user().getId()))
				.toList();
		Map<UUID, String> names = names(listed.stream().map(PeerWeeklyReport::getMentorId).collect(Collectors.toSet()));
		return listed.stream()
				.map(week -> new PeerReviewQueueItemResponse(PeerReportKind.WEEKLY, week.getId(), week.getProgramId(),
						week.getMentorId(), names.get(week.getMentorId()), week.getWeekStart(), null, null, null,
						statuses.ofWeek(week), week.getSubmittedAt()))
				.sorted(Comparator.comparing(PeerReviewQueueItemResponse::submittedAt,
						Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
						.thenComparing(PeerReviewQueueItemResponse::id))
				.toList();
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

	/**
	 * Approves a weekly summary waiting for review, and with it the week's sent daily and meeting reports of its
	 * program. Any other state (already decided, past the seven days, back with the mentor) is a conflict.
	 */
	@Transactional
	public PeerReviewedReportResponse approveWeek(UUID actorId, UUID reportId) {
		Reviewer reviewer = requireReviewer(actorId);
		PeerWeeklyReport week = reviewable(reviewer, weeklyReportRepository.findById(reportId).orElse(null), reportId);
		decide(week, reviewer, PeerReportStatus.APPROVED, null);
		approveTheWeeksReports(week, reviewer);
		return weeklyResponse(PeerReportAccess.savedOrConflict(() -> weeklyReportRepository.saveAndFlush(week)));
	}

	/**
	 * Sends a weekly summary back with a note: it reopens for its mentor ("Revizyon Gerekli"); the week's daily and
	 * meeting reports stay as sent.
	 */
	@Transactional
	public PeerReviewedReportResponse requestWeekRevision(UUID actorId, UUID reportId, String note) {
		Reviewer reviewer = requireReviewer(actorId);
		PeerWeeklyReport week = reviewable(reviewer, weeklyReportRepository.findById(reportId).orElse(null), reportId);
		decide(week, reviewer, PeerReportStatus.REVISION_REQUESTED, note.trim());
		return weeklyResponse(PeerReportAccess.savedOrConflict(() -> weeklyReportRepository.saveAndFlush(week)));
	}

	private void decide(PeerWeeklyReport week, Reviewer reviewer, PeerReportStatus outcome, String note) {
		if (statuses.ofWeek(week) != PeerReportStatus.IN_REVIEW) {
			throw new ConflictException(Texts.forRequest("error.peerReportNotInReview"));
		}
		record(week, reviewer, outcome, note);
	}

	/** The cascade: the week's sent day reports and meeting reports (of the week's program) are approved with it. */
	private void approveTheWeeksReports(PeerWeeklyReport week, Reviewer reviewer) {
		AppUser mentor = domainLookupService.requireUser(week.getMentorId());
		PeerReportScope scope = PeerReportScope.reviewer(week.getProgramId());
		dailyReportRepository.findByMentorIdAndReportDateBetween(mentor.getId(), week.getWeekStart(),
				week.getWeekStart().plusDays(6)).stream()
				.filter(day -> day.getStatus() == PeerReportStatus.IN_REVIEW)
				.filter(day -> scope.inProgram(day.getProgramId()))
				.forEach(day -> record(day, reviewer, PeerReportStatus.APPROVED, null));
		List<Meeting> meetings = access.inScope(mentor, access.heldMeetings(mentor,
				calendar.startOf(week.getWeekStart()), calendar.startOf(week.getWeekStart().plusDays(7))), scope);
		meetingReports.reportsFor(mentor, meetings, scope).values().stream()
				.filter(report -> report.getStatus() == PeerReportStatus.IN_REVIEW)
				.forEach(report -> record(report, reviewer, PeerReportStatus.APPROVED, null));
	}

	private void record(PeerReportReview report, Reviewer reviewer, PeerReportStatus outcome, String note) {
		Instant now = calendar.now();
		report.setStatus(outcome);
		report.setReviewedAt(now);
		report.setReviewedBy(reviewer.user().getId());
		report.setUpdatedAt(now);
		// An approval closes the revision round: the earlier "please change" note no longer applies.
		report.setRevisionNote(note);
	}

	private PeerMeetingReport reviewableMeetingReport(Reviewer reviewer, UUID reportId) {
		return reviewable(reviewer, meetingReportRepository.findById(reportId).orElse(null), reportId);
	}

	/**
	 * A sent report of a program the reviewer runs, not their own; a draft, one back with its mentor, a stranger's
	 * report or a missing id is not found.
	 */
	private <R extends PeerReportReview> R reviewable(Reviewer reviewer, R report, UUID reportId) {
		if (report == null || !READABLE.contains(report.getStatus()) || !reviewer.mayReview(programOf(report))
				|| reviewer.user().getId().equals(mentorOf(report))) {
			throw new ResourceNotFoundException("Peer report not found: " + reportId);
		}
		return report;
	}

	private static UUID programOf(PeerReportReview report) {
		return switch (report) {
			case PeerMeetingReport meeting -> meeting.getProgramId();
			case PeerDailyReport daily -> daily.getProgramId();
			case PeerWeeklyReport weekly -> weekly.getProgramId();
			default -> throw new IllegalStateException("Unknown report level: " + report.getClass());
		};
	}

	private static UUID mentorOf(PeerReportReview report) {
		return switch (report) {
			case PeerMeetingReport meeting -> meeting.getMentorId();
			case PeerDailyReport daily -> daily.getMentorId();
			case PeerWeeklyReport weekly -> weekly.getMentorId();
			default -> throw new IllegalStateException("Unknown report level: " + report.getClass());
		};
	}

	private PeerReviewedReportResponse meetingResponse(PeerMeetingReport report) {
		AppUser mentor = domainLookupService.requireUser(report.getMentorId());
		Meeting meeting = meetingRepository.findById(report.getMeetingId())
				.orElseThrow(() -> new ResourceNotFoundException("Peer report not found: " + report.getId()));
		return new PeerReviewedReportResponse(PeerReportKind.MEETING, report.getId(), report.getProgramId(),
				mentorOf(mentor), meetingReports.response(mentor, meeting, report, scopeOf(report.getProgramId())), null,
				null);
	}

	private PeerReviewedReportResponse dailyResponse(PeerDailyReport report) {
		AppUser mentor = domainLookupService.requireUser(report.getMentorId());
		return new PeerReviewedReportResponse(PeerReportKind.DAILY, report.getId(), report.getProgramId(),
				mentorOf(mentor), null,
				dailyReports.response(mentor, report.getReportDate(), report, scopeOf(report.getProgramId())), null);
	}

	private PeerReviewedReportResponse weeklyResponse(PeerWeeklyReport report) {
		AppUser mentor = domainLookupService.requireUser(report.getMentorId());
		return new PeerReviewedReportResponse(PeerReportKind.WEEKLY, report.getId(), report.getProgramId(),
				mentorOf(mentor), null, null,
				weeklyReports.response(mentor, report.getWeekStart(), report, scopeOf(report.getProgramId())));
	}

	private static PeerReportScope scopeOf(UUID programId) {
		return PeerReportScope.reviewer(programId);
	}

	private static PeerReviewedReportResponse.Mentor mentorOf(AppUser mentor) {
		return new PeerReviewedReportResponse.Mentor(mentor.getId(), mentor.getFullName());
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
		if (actor.getStatus() != UserAccountStatus.APPROVED) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.peerReportReviewersOnly"));
		}
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
