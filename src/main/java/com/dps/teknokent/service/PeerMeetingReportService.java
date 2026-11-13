package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.PeerMeetingFormat;
import com.dps.teknokent.domain.PeerMeetingTopic;
import com.dps.teknokent.domain.PeerMeetingType;
import com.dps.teknokent.domain.PeerMentorSkill;
import com.dps.teknokent.domain.PeerReportStatus;
import com.dps.teknokent.dto.requests.SavePeerMeetingReportRequest;
import com.dps.teknokent.dto.responses.PeerMeetingReportResponse;
import com.dps.teknokent.dto.responses.PeerReportMeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.PeerMeetingReport;
import com.dps.teknokent.entity.PeerMeetingReportAction;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.PeerMeetingReportRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * "Görüşme Raporu": one report per held peer meeting per mentee, written by the meeting's peer mentor. The meeting's
 * facts are filled in from the calendar; the report is the mentor's and the program coordinators', never the mentee's.
 */
@Service
@RequiredArgsConstructor
public class PeerMeetingReportService {

	/** The longest window the meetings list answers for, so a client cannot ask for years of rows at once. */
	private static final int MAX_RANGE_DAYS = 62;

	private final PeerReportAccess access;
	private final PeerReportCalendar calendar;
	private final PeerMeetingReportRepository reportRepository;
	private final PeerMentoringService peerMentoringService;

	/** The mentor's held peer meetings from {@code from} to {@code to} (both days included), oldest first. */
	@Transactional(readOnly = true)
	public List<PeerReportMeetingResponse> meetings(UUID actorId, LocalDate from, LocalDate to) {
		AppUser mentor = access.requireMentor(actorId);
		PeerReportCalendar.requirePlausible(from);
		PeerReportCalendar.requirePlausible(to);
		if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
			throw new InvalidRequestException(Texts.forRequest("error.peerReportRange"));
		}
		return rows(mentor, access.heldMeetings(mentor, calendar.startOf(from), calendar.startOf(to.plusDays(1))));
	}

	@Transactional(readOnly = true)
	public PeerMeetingReportResponse get(UUID actorId, UUID meetingId) {
		AppUser mentor = access.requireMentor(actorId);
		Meeting meeting = access.requireHeldMeeting(mentor, meetingId);
		return reportOf(meeting)
				.map(report -> response(mentor, meeting, report))
				.orElseGet(() -> response(mentor, meeting, prefill(mentor, meeting)));
	}

	@Transactional
	public PeerMeetingReportResponse save(UUID actorId, UUID meetingId, SavePeerMeetingReportRequest request) {
		AppUser mentor = access.requireMentor(actorId);
		Meeting meeting = access.requireHeldMeeting(mentor, meetingId);
		PeerMeetingReport report = reportOf(meeting).orElseGet(() -> {
			PeerMeetingReport created = prefill(mentor, meeting);
			created.setId(UUID.randomUUID());
			created.setCreatedAt(calendar.now());
			return created;
		});
		requireEditable(report);
		apply(report, request);
		report.setUpdatedAt(calendar.now());
		return response(mentor, meeting,
				PeerReportAccess.savedOrConflict(() -> reportRepository.saveAndFlush(report)));
	}

	/** Sends the report for review; refused, naming the sections still missing, while a required answer is empty. */
	@Transactional
	public PeerMeetingReportResponse submit(UUID actorId, UUID meetingId) {
		AppUser mentor = access.requireMentor(actorId);
		Meeting meeting = access.requireHeldMeeting(mentor, meetingId);
		PeerMeetingReport report = reportOf(meeting)
				.orElseThrow(() -> new ConflictException(Texts.forRequest("error.peerReportNotStarted")));
		requireEditable(report);
		List<String> missing = missingSections(report);
		if (!missing.isEmpty()) {
			throw new InvalidRequestException(
					Texts.forRequest("error.peerReportIncomplete", String.join(", ", missing)), "PEER_REPORT_INCOMPLETE");
		}
		report.submit(calendar.now());
		return response(mentor, meeting,
				PeerReportAccess.savedOrConflict(() -> reportRepository.saveAndFlush(report)));
	}

	/** Ticks an action step; allowed after sending too, since the weekly summary counts the done ones. */
	@Transactional
	public PeerMeetingReportResponse updateAction(UUID actorId, UUID meetingId, UUID actionId, boolean done) {
		AppUser mentor = access.requireMentor(actorId);
		Meeting meeting = access.requireHeldMeeting(mentor, meetingId);
		PeerMeetingReport report = reportOf(meeting).orElseThrow(() -> actionNotFound(actionId));
		PeerMeetingReportAction action = report.getActions().stream()
				.filter(candidate -> candidate.getId().equals(actionId))
				.findFirst()
				.orElseThrow(() -> actionNotFound(actionId));
		if (done != (action.getDoneAt() != null)) {
			action.setDoneAt(done ? calendar.now() : null);
			report.setUpdatedAt(calendar.now());
		}
		return response(mentor, meeting,
				PeerReportAccess.savedOrConflict(() -> reportRepository.saveAndFlush(report)));
	}

	/** The list rows for these meetings: the report's own type, format and duration once it has them. */
	List<PeerReportMeetingResponse> rows(AppUser mentor, List<Meeting> meetings) {
		return rows(mentor, meetings, PeerReportScope.MENTOR);
	}

	List<PeerReportMeetingResponse> rows(AppUser mentor, List<Meeting> meetings, PeerReportScope scope) {
		Map<UUID, PeerMeetingReport> reports = reportsFor(mentor, meetings, scope);
		Map<UUID, PeerMeetingType> types = types(mentor, meetings, reports);
		return meetings.stream().map(meeting -> {
			PeerMeetingReport report = reports.get(meeting.getId());
			AppUser mentee = meeting.getMentorshipRequest().getRequester();
			return new PeerReportMeetingResponse(
					meeting.getId(),
					mentee.getId(),
					mentee.getFullName(),
					meeting.getScheduledAt(),
					durationOf(meeting, report),
					types.get(meeting.getId()),
					report != null && report.getMeetingFormat() != null ? report.getMeetingFormat()
							: defaultFormat(meeting),
					report == null ? null : report.getStatus());
		}).toList();
	}

	/** The reports of these meetings by meeting id. A peer meeting has one mentee: the request's requester. */
	Map<UUID, PeerMeetingReport> reportsFor(AppUser mentor, Collection<Meeting> meetings) {
		return reportsFor(mentor, meetings, PeerReportScope.MENTOR);
	}

	/** As above, keeping only what the scope may see (a reviewer: sent reports only). */
	Map<UUID, PeerMeetingReport> reportsFor(AppUser mentor, Collection<Meeting> meetings, PeerReportScope scope) {
		if (meetings.isEmpty()) {
			return Map.of();
		}
		Map<UUID, UUID> menteeOf = meetings.stream().collect(Collectors.toMap(Meeting::getId,
				meeting -> meeting.getMentorshipRequest().getRequester().getId(), (first, second) -> first));
		return reportRepository.findByMentorIdAndMeetingIdIn(mentor.getId(), menteeOf.keySet()).stream()
				.filter(report -> report.getMenteeId().equals(menteeOf.get(report.getMeetingId())))
				.filter(scope::shows)
				.collect(Collectors.toMap(PeerMeetingReport::getMeetingId, Function.identity()));
	}

	/** The minutes a meeting counts for: the reported duration once there is one, the planned one before. */
	static int durationOf(Meeting meeting, PeerMeetingReport report) {
		return report != null && report.getActualDurationMinutes() != null ? report.getActualDurationMinutes()
				: meeting.getDurationMinutes();
	}

	/** Whether the report counts as sent for the day and week gates. */
	static boolean sent(PeerMeetingReport report) {
		return report != null && report.getStatus() != null && report.getStatus().sent();
	}

	/**
	 * Each meeting's type: the report's when it has one, else the calendar's (a meeting after an earlier one with the
	 * same mentee is a follow-up). One lookup per mentee, not per meeting.
	 */
	Map<UUID, PeerMeetingType> types(AppUser mentor, List<Meeting> meetings, Map<UUID, PeerMeetingReport> reports) {
		Map<UUID, Instant> firstWith = new HashMap<>();
		Map<UUID, PeerMeetingType> types = new HashMap<>();
		for (Meeting meeting : meetings) {
			PeerMeetingReport report = reports.get(meeting.getId());
			if (report != null && report.getMeetingType() != null) {
				types.put(meeting.getId(), report.getMeetingType());
				continue;
			}
			Instant first = firstWith.computeIfAbsent(
					meeting.getMentorshipRequest().getRequester().getId(),
					menteeId -> access.meetingsWith(mentor, menteeId).getFirst().getScheduledAt());
			types.put(meeting.getId(),
					meeting.getScheduledAt().isAfter(first) ? PeerMeetingType.FOLLOW_UP : PeerMeetingType.FIRST);
		}
		return types;
	}

	private Optional<PeerMeetingReport> reportOf(Meeting meeting) {
		return reportRepository.findByMeetingIdAndMenteeId(meeting.getId(),
				meeting.getMentorshipRequest().getRequester().getId());
	}

	/** An unsaved report with what the calendar knows: type, format and the planned duration. */
	private PeerMeetingReport prefill(AppUser mentor, Meeting meeting) {
		PeerMeetingReport report = new PeerMeetingReport();
		report.setMeetingId(meeting.getId());
		report.setMentorId(mentor.getId());
		report.setMenteeId(meeting.getMentorshipRequest().getRequester().getId());
		report.setProgramId(access.programOf(mentor, meeting));
		report.setMeetingType(defaultType(mentor, meeting));
		report.setMeetingFormat(defaultFormat(meeting));
		report.setActualDurationMinutes(meeting.getDurationMinutes());
		report.setStatus(null);
		return report;
	}

	private PeerMeetingType defaultType(AppUser mentor, Meeting meeting) {
		boolean earlier = access.meetingsWith(mentor, meeting.getMentorshipRequest().getRequester().getId()).stream()
				.anyMatch(other -> other.getScheduledAt().isBefore(meeting.getScheduledAt()));
		return earlier ? PeerMeetingType.FOLLOW_UP : PeerMeetingType.FIRST;
	}

	/** The request's meeting mode; with no preference, a meeting with a link was held online. */
	private static PeerMeetingFormat defaultFormat(Meeting meeting) {
		MeetingMode mode = meeting.getMentorshipRequest().getMeetingMode();
		if (mode == MeetingMode.IN_PERSON) {
			return PeerMeetingFormat.IN_PERSON;
		}
		if (mode == MeetingMode.ONLINE || (meeting.getMeetLink() != null && !meeting.getMeetLink().isBlank())) {
			return PeerMeetingFormat.ONLINE;
		}
		return null;
	}

	private static void requireEditable(PeerMeetingReport report) {
		if (report.getStatus() != null && !report.getStatus().editable()) {
			throw new ConflictException(Texts.forRequest("error.peerReportLocked"));
		}
	}

	private void apply(PeerMeetingReport report, SavePeerMeetingReportRequest request) {
		report.setMeetingType(request.meetingType());
		report.setMeetingFormat(request.meetingFormat());
		report.setActualDurationMinutes(request.actualDurationMinutes());
		replace(report.getTopics(), request.topics());
		report.setMenteeNeed(trimmed(request.menteeNeed()));
		report.setDiscussion(trimmed(request.discussion()));
		report.setApproach(trimmed(request.approach()));
		replace(report.getSkills(), request.skills());
		report.setSkillExample(trimmed(request.skillExample()));
		report.setOutcome(trimmed(request.outcome()));
		applyActions(report, request.actions() == null ? List.of() : request.actions());
		report.setFollowUpNeeded(request.followUpNeeded());
		report.setFollowUpAt(request.followUpNeeded() ? request.followUpAt() : null);
		report.setProductivity(request.productivity());
		report.setNextTime(trimmed(request.nextTime()));
		report.setCoordinatorSupportNeeded(request.coordinatorSupportNeeded());
		if (report.getStatus() == null) {
			report.setStatus(PeerReportStatus.DRAFT);
		}
	}

	/** Keeps the steps the body names by id (with their done time), adds the new ones, drops the rest. */
	private void applyActions(PeerMeetingReport report, List<SavePeerMeetingReportRequest.Action> wanted) {
		Map<UUID, PeerMeetingReportAction> existing = new HashMap<>();
		report.getActions().forEach(action -> existing.put(action.getId(), action));
		List<PeerMeetingReportAction> next = new ArrayList<>();
		for (SavePeerMeetingReportRequest.Action input : wanted) {
			PeerMeetingReportAction action = Optional.ofNullable(input.id())
					.map(existing::remove)
					.orElseGet(() -> {
						PeerMeetingReportAction created = new PeerMeetingReportAction();
						created.setId(UUID.randomUUID());
						created.setReport(report);
						return created;
					});
			action.setPosition(next.size());
			action.setText(input.text().trim());
			action.setOwner(input.owner());
			action.setDueDate(input.dueDate());
			if (input.done() != (action.getDoneAt() != null)) {
				action.setDoneAt(input.done() ? calendar.now() : null);
			}
			next.add(action);
		}
		report.getActions().clear();
		report.getActions().addAll(next);
	}

	/** The Figma's required ("*") answers, by section, in the form's order. */
	private static List<String> missingSections(PeerMeetingReport report) {
		List<String> missing = new ArrayList<>();
		if (report.getMeetingType() == null || report.getMeetingFormat() == null
				|| report.getActualDurationMinutes() == null) {
			missing.add("MEETING");
		}
		if (report.getTopics().isEmpty() || blank(report.getMenteeNeed())) {
			missing.add("TOPIC");
		}
		if (blank(report.getDiscussion()) || blank(report.getApproach())) {
			missing.add("DETAILS");
		}
		if (report.getSkills().isEmpty() || blank(report.getSkillExample())) {
			missing.add("SKILLS");
		}
		if (blank(report.getOutcome()) || (report.isFollowUpNeeded() && report.getFollowUpAt() == null)) {
			missing.add("OUTCOMES");
		}
		if (report.getProductivity() == null || blank(report.getNextTime())
				|| report.getCoordinatorSupportNeeded() == null) {
			missing.add("EVALUATION");
		}
		return missing;
	}

	private PeerMeetingReportResponse response(AppUser mentor, Meeting meeting, PeerMeetingReport report) {
		return response(mentor, meeting, report, PeerReportScope.MENTOR);
	}

	PeerMeetingReportResponse response(AppUser mentor, Meeting meeting, PeerMeetingReport report,
			PeerReportScope scope) {
		AppUser mentee = meeting.getMentorshipRequest().getRequester();
		PeerReportStatus status = report.getStatus();
		return new PeerMeetingReportResponse(
				report.getId(),
				meeting.getId(),
				status,
				status == null || status.editable(),
				new PeerMeetingReportResponse.Mentee(mentee.getId(), mentee.getFullName(),
						peerMentoringService.departmentOf(mentee.getId(), null)),
				meeting.getScheduledAt(),
				meeting.getDurationMinutes(),
				context(mentor, meeting, scope),
				report.getMeetingType(),
				report.getMeetingFormat(),
				report.getActualDurationMinutes(),
				ordered(report.getTopics(), PeerMeetingTopic.class),
				report.getMenteeNeed(),
				report.getDiscussion(),
				report.getApproach(),
				ordered(report.getSkills(), PeerMentorSkill.class),
				report.getSkillExample(),
				report.getOutcome(),
				report.getActions().stream().map(PeerMeetingReportService::action).toList(),
				report.isFollowUpNeeded(),
				report.getFollowUpAt(),
				report.getProductivity(),
				report.getNextTime(),
				report.getCoordinatorSupportNeeded(),
				report.getSubmittedAt(),
				report.getRevisionNote(),
				report.getUpdatedAt());
	}

	/** The side card: this meeting's number with the mentee, the meeting before it, and steps still open from before. */
	private PeerMeetingReportResponse.Context context(AppUser mentor, Meeting meeting, PeerReportScope scope) {
		UUID menteeId = meeting.getMentorshipRequest().getRequester().getId();
		List<Meeting> earlier = access.inScope(mentor, access.meetingsWith(mentor, menteeId).stream()
				.filter(other -> other.getScheduledAt().isBefore(meeting.getScheduledAt()))
				.toList(), scope);
		if (earlier.isEmpty()) {
			return new PeerMeetingReportResponse.Context(1, null, 0);
		}
		Map<UUID, PeerMeetingReport> reports = reportsFor(mentor, earlier, scope);
		Meeting previous = earlier.getLast();
		PeerMeetingReport previousReport = reports.get(previous.getId());
		int openActions = (int) reports.values().stream()
				.flatMap(report -> report.getActions().stream())
				.filter(action -> action.getDoneAt() == null)
				.count();
		return new PeerMeetingReportResponse.Context(
				earlier.size() + 1,
				new PeerMeetingReportResponse.PreviousMeeting(previous.getId(), previous.getScheduledAt(),
						previousReport == null ? Set.of() : ordered(previousReport.getTopics(), PeerMeetingTopic.class)),
				openActions);
	}

	private static PeerMeetingReportResponse.Action action(PeerMeetingReportAction action) {
		return new PeerMeetingReportResponse.Action(action.getId(), action.getText(), action.getOwner(),
				action.getDueDate(), action.getDoneAt() != null);
	}

	private static ResourceNotFoundException actionNotFound(UUID actionId) {
		return new ResourceNotFoundException("Peer report action not found: " + actionId);
	}

	/** A copy in the enum's declared order, so responses list chips the way the form shows them. */
	static <E extends Enum<E>> Set<E> ordered(Set<E> values, Class<E> type) {
		EnumSet<E> copy = EnumSet.noneOf(type);
		copy.addAll(values);
		return copy;
	}

	private static <E> void replace(Set<E> target, Set<E> values) {
		target.clear();
		if (values != null) {
			target.addAll(values);
		}
	}

	static String trimmed(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
