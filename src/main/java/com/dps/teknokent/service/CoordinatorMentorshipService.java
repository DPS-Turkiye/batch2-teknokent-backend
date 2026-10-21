package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.CoordinatorMentorshipHealth;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.dto.responses.CoordinatorMentorshipResponse;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipGoal;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipGoalRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.service.CoordinatorProgramService.HealthBucket;
import com.dps.teknokent.service.CoordinatorProgramService.Snapshot;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * The coordinator's Mentorluklar table and drawer: every planned or active mentorship of the teams in their
 * programs, graded with the same cadence rules as the program health card.
 */
@Service
@RequiredArgsConstructor
public class CoordinatorMentorshipService {

	private static final Set<MentorshipStatus> LISTED = EnumSet.of(MentorshipStatus.PLANNED, MentorshipStatus.ACTIVE);
	private static final List<ActionItemStatus> CLOSED_ACTIONS = List.of(ActionItemStatus.COMPLETED, ActionItemStatus.CANCELLED);
	private static final List<CoordinatorMentorshipHealth> RISK_ORDER = List.of(
			CoordinatorMentorshipHealth.RISKY,
			CoordinatorMentorshipHealth.FOLLOW_UP,
			CoordinatorMentorshipHealth.NEW,
			CoordinatorMentorshipHealth.HEALTHY);

	private final CoordinatorProgramService programService;
	private final MentorshipRepository mentorshipRepository;
	private final ActionItemRepository actionItemRepository;
	private final MeetingNoteRepository meetingNoteRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final MentorshipGoalRepository mentorshipGoalRepository;
	private final MentorProfileRepository mentorProfileRepository;
	private final AppUserRepository appUserRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final NotificationService notificationService;

	/** Riskiest first; within a bucket, the longest silence first. */
	@Transactional(readOnly = true)
	public List<CoordinatorMentorshipResponse> list(UUID actorId, UUID programId) {
		List<Program> managed = programService.managedPrograms(programService.requireCoordinatorOrAdmin(actorId));
		List<Program> scope = programService.scopeOf(actorId, managed, programId);
		Snapshot snapshot = programService.snapshot(scope);
		Context context = context(scope, snapshot);
		return context.programOf().entrySet().stream()
				.flatMap(entry -> CoordinatorProgramService.mentorshipsOf(snapshot, entry.getKey()).stream()
						.filter(mentorship -> LISTED.contains(mentorship.getStatus()))
						.map(mentorship -> row(mentorship, entry.getValue(), snapshot, context)))
				.sorted(Comparator
						.comparingInt((CoordinatorMentorshipResponse row) -> RISK_ORDER.indexOf(row.health()))
						.thenComparing(
								CoordinatorMentorshipResponse::daysSinceLastMeeting,
								Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
	}

	@Transactional(readOnly = true)
	public CoordinatorMentorshipResponse.Detail detail(UUID actorId, UUID mentorshipId) {
		Scoped scoped = requireManagedMentorship(actorId, mentorshipId);
		Mentorship mentorship = scoped.mentorship();
		Program program = scoped.program();
		Snapshot snapshot = programService.snapshot(List.of(program));
		Context context = context(List.of(program), snapshot);
		CoordinatorMentorshipResponse row = row(mentorship, program, snapshot, context);

		List<Meeting> meetings = pairMeetings(mentorship, snapshot);
		List<Meeting> past = meetings.stream().filter(meeting -> isPast(meeting, snapshot.now())).toList();
		List<Meeting> noted = past.stream().filter(meeting -> isNoted(meeting, context)).toList();
		List<MentorshipGoal> goals = mentorshipGoalRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorship.getId());
		UUID mentorId = mentorship.getMentor().getId();
		long load = mentorshipRepository.countOrganizationsByMentor(List.of(mentorId), LISTED).stream()
				.mapToLong(MentorCount::getTotal)
				.sum();
		Integer capacity = mentorProfileRepository.findById(mentorId).map(MentorProfile::getMenteeCapacity).orElse(null);
		AppUser founder = mentorship.getOrganization().getFounder();
		int members = appUserRepository.findByOrganizationIdOrderByFullNameAsc(mentorship.getOrganization().getId()).size();

		return new CoordinatorMentorshipResponse.Detail(
				row,
				program.getMeetingFrequencyDays(),
				program.getStaleAfterDays(),
				past.size(),
				noted.size(),
				noted.stream().map(Meeting::getScheduledAt).max(Comparator.naturalOrder()).orElse(null),
				(int) goals.stream().filter(MentorshipGoal::isDone).count(),
				goals.size(),
				load,
				capacity,
				founder == null ? null : founder.getFullName(),
				members,
				meetings.stream()
						.map(meeting -> new CoordinatorMentorshipResponse.MeetingRow(
								meeting.getId(),
								CoordinatorProgramService.meetingTitle(meeting),
								meeting.getScheduledAt(),
								meeting.getDurationMinutes(),
								meeting.getStatus(),
								isNoted(meeting, context)))
						.toList());
	}

	/** "İki tarafa hatırlat": nudges the mentor and every member of the team to book their next meeting. */
	@Transactional
	public void remind(UUID actorId, UUID mentorshipId) {
		Scoped scoped = requireManagedMentorship(actorId, mentorshipId);
		Mentorship mentorship = scoped.mentorship();
		String pair = mentorship.getMentor().getFullName() + " ↔ " + mentorship.getOrganization().getName();
		String program = scoped.program().getName();
		notificationService.create(
				mentorship.getMentor(), NotificationCategory.MEETING_REMINDER,
				Texts.get(mentorship.getMentor(), "notification.coordinatorReminder.title"),
				Texts.get(mentorship.getMentor(), "notification.coordinatorReminder.body", program, pair),
				"/mentor-dashboard/calendar");
		for (AppUser member : appUserRepository.findByOrganizationIdOrderByFullNameAsc(mentorship.getOrganization().getId())) {
			notificationService.create(
					member, NotificationCategory.MEETING_REMINDER, Texts.get(member, "notification.coordinatorReminder.title"),
					Texts.get(member, "notification.coordinatorReminder.body", program, pair), "/mentorships?tab=active");
		}
	}

	private CoordinatorMentorshipResponse row(Mentorship mentorship, Program program, Snapshot snapshot, Context context) {
		UUID mentorId = mentorship.getMentor().getId();
		UUID organizationId = mentorship.getOrganization().getId();
		String pairKey = CoordinatorProgramService.pairKey(mentorId, organizationId);
		Instant last = snapshot.lastMeetingByPair().get(pairKey);
		List<Meeting> meetings = pairMeetings(mentorship, snapshot);
		Instant next = meetings.stream()
				.filter(meeting -> meeting.getStatus() == MeetingStatus.SCHEDULED
						&& meeting.getScheduledAt() != null
						&& !meeting.getScheduledAt().isBefore(snapshot.now()))
				.map(Meeting::getScheduledAt)
				.min(Comparator.naturalOrder())
				.orElse(null);
		// Actions raised in another mentor's meeting belong to that mentorship, not this one.
		List<ActionItem> actions = context.openActions().getOrDefault(organizationId, List.of()).stream()
				.filter(item -> item.getMeeting() == null
						|| item.getMeeting().getMentor() == null
						|| item.getMeeting().getMentor().getId().equals(mentorId))
				.toList();
		int overdue = (int) actions.stream()
				.filter(item -> item.getDueDate() != null && item.getDueDate().isBefore(snapshot.today()))
				.count();
		int missingNotes = (int) meetings.stream()
				.filter(meeting -> isPast(meeting, snapshot.now()) && !isNoted(meeting, context))
				.count();
		return new CoordinatorMentorshipResponse(
				mentorship.getId(),
				program.getId(),
				program.getName(),
				mentorId,
				mentorship.getMentor().getFullName(),
				mentorship.getMentor().getJobTitle(),
				organizationId,
				mentorship.getOrganization().getName(),
				mentorship.getStatus(),
				health(mentorship, program, snapshot),
				context.topics().get(pairKey),
				mentorship.getStartDate(),
				last,
				last == null ? null : Math.max(0, ChronoUnit.DAYS.between(last, snapshot.now())),
				next,
				actions.size(),
				overdue,
				missingNotes);
	}

	/** Uses the team-level issue flags so the table agrees with the program's health card. */
	private static CoordinatorMentorshipHealth health(Mentorship mentorship, Program program, Snapshot snapshot) {
		if (mentorship.getStatus() != MentorshipStatus.ACTIVE) {
			return CoordinatorMentorshipHealth.NEW;
		}
		UUID organizationId = mentorship.getOrganization().getId();
		boolean hasOpenIssue = snapshot.overdueActionOrganizations().contains(organizationId)
				|| !snapshot.openBottlenecks().getOrDefault(organizationId, List.of()).isEmpty();
		HealthBucket bucket = CoordinatorProgramService.classify(
				CoordinatorProgramService.daysSinceContact(mentorship, snapshot),
				hasOpenIssue,
				program.getMeetingFrequencyDays(),
				program.getStaleAfterDays());
		return switch (bucket) {
			case HEALTHY -> CoordinatorMentorshipHealth.HEALTHY;
			case FOLLOW_UP -> CoordinatorMentorshipHealth.FOLLOW_UP;
			case RISKY -> CoordinatorMentorshipHealth.RISKY;
		};
	}

	private Context context(List<Program> scope, Snapshot snapshot) {
		Map<UUID, Program> programOf = new LinkedHashMap<>();
		for (Program program : scope) {
			for (ProgramStartup startup : snapshot.startups().getOrDefault(program.getId(), List.of())) {
				programOf.putIfAbsent(startup.getOrganizationId(), program);
			}
		}
		if (programOf.isEmpty()) {
			return new Context(programOf, Map.of(), Set.of(), Map.of());
		}
		Set<UUID> organizationIds = programOf.keySet();
		Map<UUID, List<ActionItem>> openActions = actionItemRepository
				.findByOrganizationIdInAndStatusNotIn(organizationIds, CLOSED_ACTIONS)
				.stream()
				.collect(Collectors.groupingBy(item -> item.getOrganization().getId()));
		List<UUID> pastMeetingIds = snapshot.meetingsByOrganization().values().stream()
				.flatMap(List::stream)
				.filter(meeting -> isPast(meeting, snapshot.now()))
				.map(Meeting::getId)
				.toList();
		Set<UUID> liveNoted = pastMeetingIds.isEmpty()
				? Set.of()
				: meetingNoteRepository.findByMeetingIdIn(pastMeetingIds).stream()
						.filter(note -> !CoordinatorProgramService.isBlank(note.getContent()))
						.map(note -> note.getMeeting().getId())
						.collect(Collectors.toSet());
		// The topic is the support area of the latest accepted request between the pair.
		Map<String, String> topics = new HashMap<>();
		mentorshipRequestRepository.findByOrganizationIdInAndStatus(organizationIds, MentorshipRequestStatus.ACCEPTED)
				.stream()
				.sorted(Comparator.comparing(
						MentorshipRequest::getCreatedAt,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.forEach(request -> topics.putIfAbsent(
						CoordinatorProgramService.pairKey(request.getMentor().getId(), request.getOrganization().getId()),
						request.getSupportArea()));
		return new Context(programOf, openActions, liveNoted, topics);
	}

	/** The mentorship, and the actor's program it belongs to; rejects mentorships outside their programs. */
	public Scoped requireManagedMentorship(UUID actorId, UUID mentorshipId) {
		AppUser actor = programService.requireCoordinatorOrAdmin(actorId);
		Mentorship mentorship = mentorshipRepository.findById(mentorshipId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship not found: " + mentorshipId));
		Map<UUID, Program> managed = programService.managedPrograms(actor).stream()
				.collect(Collectors.toMap(Program::getId, program -> program, (first, second) -> first, LinkedHashMap::new));
		UUID organizationId = mentorship.getOrganization().getId();
		Program program = managed.isEmpty()
				? null
				: programStartupRepository.findByProgramIdIn(managed.keySet()).stream()
						.filter(startup -> startup.getOrganizationId().equals(organizationId))
						.map(startup -> managed.get(startup.getProgramId()))
						.findFirst()
						.orElse(null);
		if (program == null) {
			throw new InvalidRequestException("This mentorship is not in one of your programs.");
		}
		return new Scoped(mentorship, program);
	}

	private static List<Meeting> pairMeetings(Mentorship mentorship, Snapshot snapshot) {
		UUID mentorId = mentorship.getMentor().getId();
		return snapshot.meetingsByOrganization().getOrDefault(mentorship.getOrganization().getId(), List.of()).stream()
				.filter(meeting -> meeting.getMentor() != null && meeting.getMentor().getId().equals(mentorId))
				.toList();
	}

	private static boolean isPast(Meeting meeting, Instant now) {
		return meeting.getStatus() != MeetingStatus.CANCELLED
				&& meeting.getScheduledAt() != null
				&& meeting.getScheduledAt().isBefore(now);
	}

	private static boolean isNoted(Meeting meeting, Context context) {
		return !CoordinatorProgramService.isBlank(meeting.getNotes())
				|| !CoordinatorProgramService.isBlank(meeting.getOutcomes())
				|| context.liveNoted().contains(meeting.getId());
	}

	private record Context(
			Map<UUID, Program> programOf,
			Map<UUID, List<ActionItem>> openActions,
			Set<UUID> liveNoted,
			Map<String, String> topics) {
	}

	public record Scoped(Mentorship mentorship, Program program) {
	}
}
