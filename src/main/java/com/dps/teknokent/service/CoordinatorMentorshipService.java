package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.CoordinatorMentorshipHealth;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipSource;
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
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
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
import com.dps.teknokent.util.MeetingSlotTimes;
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
	private static final List<ActionItemStatus> CLOSED_ACTIONS = List.of(ActionItemStatus.COMPLETED,
			ActionItemStatus.CANCELLED);
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
		// Each mentorship is listed once, under the program it was made in, also after its team left that program.
		// One made before V82 has no program and is listed under the first of the team's programs here, as before.
		Set<UUID> listed = new HashSet<>();
		// A team working with a mentor through an accepted request has no mentorship row, so the request is listed,
		// once per team, mentor and program, as the home counters count it.
		Set<String> listedRequests = new HashSet<>();
		List<CoordinatorMentorshipResponse> rows = new ArrayList<>();
		for (Program program : scope) {
			CoordinatorProgramService.programMentorships(snapshot, program).stream()
					.filter(mentorship -> LISTED.contains(mentorship.getStatus()))
					.filter(mentorship -> listed.add(mentorship.getId()))
					.map(mentorship -> row(mentorship, program, snapshot, context))
					.forEach(rows::add);
			CoordinatorProgramService.acceptedRequestPairs(snapshot, program).stream()
					.filter(request -> listedRequests.add(CoordinatorProgramService.requestPairKey(request)))
					.map(request -> row(request, program, snapshot, context))
					.forEach(rows::add);
		}
		return rows.stream()
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

		List<Meeting> meetings = pairMeetings(mentorship.getMentor().getId(), mentorship.getOrganization().getId(),
				program, snapshot);
		List<Meeting> past = meetings.stream().filter(meeting -> isPast(meeting, snapshot.now())).toList();
		List<Meeting> noted = past.stream().filter(meeting -> isNoted(meeting, context)).toList();
		List<MentorshipGoal> goals = mentorshipGoalRepository
				.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorship.getId());
		UUID mentorId = mentorship.getMentor().getId();
		long load = mentorshipRepository.countOrganizationsByMentor(List.of(mentorId), LISTED).stream()
				.mapToLong(MentorCount::getTotal)
				.sum()
				+ programService.acceptedRequestLoad(List.of(mentorId)).getOrDefault(mentorId, 0L);
		Integer capacity = mentorProfileRepository.findById(mentorId).map(MentorProfile::getMenteeCapacity)
				.orElse(null);
		AppUser founder = mentorship.getOrganization().getFounder();
		int members = appUserRepository.findByOrganizationIdOrderByFullNameAsc(mentorship.getOrganization().getId())
				.size();

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
								isNoted(meeting, context),
								meeting.getNoShowAt() != null,
								meeting.getNoShowNote()))
						.toList());
	}

	/** "İki tarafa hatırlat": nudges the mentor and every member of the team to book their next meeting. */
	@Transactional
	public void remind(UUID actorId, UUID mentorshipId) {
		Scoped scoped = requireManagedMentorship(actorId, mentorshipId);
		Mentorship mentorship = scoped.mentorship();
		String pair = mentorship.getMentor().getFullName() + " ↔ " + mentorship.getOrganization().getName();
		String program = scoped.program().getName();
		notificationService.createAndMail(
				mentorship.getMentor(), NotificationCategory.MEETING_REMINDER,
				Texts.get(mentorship.getMentor(), "notification.coordinatorReminder.title"),
				Texts.get(mentorship.getMentor(), "notification.coordinatorReminder.body", program, pair),
				"/mentor-dashboard/calendar");
		for (AppUser member : appUserRepository
				.findByOrganizationIdOrderByFullNameAsc(mentorship.getOrganization().getId())) {
			notificationService.createAndMail(
					member, NotificationCategory.MEETING_REMINDER,
					Texts.get(member, "notification.coordinatorReminder.title"),
					Texts.get(member, "notification.coordinatorReminder.body", program, pair),
					"/mentorships?tab=active");
		}
	}

	private CoordinatorMentorshipResponse row(Mentorship mentorship, Program program, Snapshot snapshot,
			Context context) {
		UUID organizationId = mentorship.getOrganization().getId();
		String pairKey = CoordinatorProgramService.pairKey(mentorship.getMentor().getId(), organizationId);
		return row(
				new Pair(mentorship.getId(), null, MentorshipSource.MENTORSHIP, mentorship.getMentor(),
						mentorship.getOrganization(), mentorship.getStatus(), mentorship.getStartDate(),
						CoordinatorProgramService.daysSinceContact(mentorship, snapshot),
						context.topics().get(pairKey)),
				program, snapshot, context);
	}

	/** A request has no state of its own past acceptance: the pair is graded as an active mentorship from then. */
	private CoordinatorMentorshipResponse row(MentorshipRequest request, Program program, Snapshot snapshot,
			Context context) {
		Instant accepted = CoordinatorProgramService.acceptedOn(request);
		return row(
				new Pair(null, request.getId(), MentorshipSource.ACCEPTED_REQUEST, request.getMentor(),
						request.getOrganization(), MentorshipStatus.ACTIVE,
						accepted == null ? snapshot.today() : accepted.atZone(MeetingSlotTimes.APP_ZONE).toLocalDate(),
						CoordinatorProgramService.daysSinceContact(request, snapshot), request.getSupportArea()),
				program, snapshot, context);
	}

	private CoordinatorMentorshipResponse row(Pair pair, Program program, Snapshot snapshot, Context context) {
		UUID mentorId = pair.mentor().getId();
		UUID organizationId = pair.organization().getId();
		String pairKey = CoordinatorProgramService.pairKey(mentorId, organizationId);
		Instant last = snapshot.lastMeetingByPair().get(pairKey);
		List<Meeting> meetings = pairMeetings(mentorId, organizationId, program, snapshot);
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
				pair.mentorshipId(),
				program.getId(),
				program.getName(),
				mentorId,
				pair.mentor().getFullName(),
				pair.mentor().getJobTitle(),
				organizationId,
				pair.organization().getName(),
				pair.status(),
				health(pair, program, snapshot),
				pair.topic(),
				pair.startDate(),
				last,
				last == null ? null : Math.max(0, ChronoUnit.DAYS.between(last, snapshot.now())),
				next,
				actions.size(),
				overdue,
				missingNotes,
				pair.source(),
				pair.requestId());
	}

	/** Uses the team-level issue flags so the table agrees with the program's health card. */
	private static CoordinatorMentorshipHealth health(Pair pair, Program program, Snapshot snapshot) {
		if (pair.status() != MentorshipStatus.ACTIVE) {
			return CoordinatorMentorshipHealth.NEW;
		}
		UUID organizationId = pair.organization().getId();
		boolean hasOpenIssue = snapshot.overdueActionOrganizations().contains(organizationId)
				|| !snapshot.openBottlenecks().getOrDefault(organizationId, List.of()).isEmpty();
		HealthBucket bucket = CoordinatorProgramService.classify(
				pair.daysSinceContact(),
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
			for (UUID organizationId : CoordinatorProgramService.activityTeams(snapshot, program)) {
				programOf.putIfAbsent(organizationId, program);
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
						CoordinatorProgramService.pairKey(request.getMentor().getId(),
								request.getOrganization().getId()),
						request.getSupportArea()));
		return new Context(programOf, openActions, liveNoted, topics);
	}

	/** The mentorship, and the actor's program it belongs to; rejects mentorships outside their programs. */
	public Scoped requireManagedMentorship(UUID actorId, UUID mentorshipId) {
		AppUser actor = programService.requireCoordinatorOrAdmin(actorId);
		Mentorship mentorship = mentorshipRepository.findById(mentorshipId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship not found: " + mentorshipId));
		Map<UUID, Program> managed = programService.managedPrograms(actor).stream()
				.collect(Collectors.toMap(Program::getId, program -> program, (first, second) -> first,
						LinkedHashMap::new));
		if (mentorship.getProgram() != null) {
			Program program = managed.get(mentorship.getProgram().getId());
			if (program == null) {
				throw new InvalidRequestException("This mentorship is not in one of your programs.");
			}
			return new Scoped(mentorship, program);
		}
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

	/** The pair's meetings that count in the mentorship's program; a team can work with one mentor in two programs. */
	private static List<Meeting> pairMeetings(UUID mentorId, UUID organizationId, Program program, Snapshot snapshot) {
		return snapshot.meetingsByOrganization().getOrDefault(organizationId, List.of()).stream()
				.filter(meeting -> meeting.getMentor() != null && meeting.getMentor().getId().equals(mentorId))
				.filter(meeting -> CoordinatorProgramService.countsIn(meeting, program, snapshot))
				.toList();
	}

	private static boolean isPast(Meeting meeting, Instant now) {
		return !meeting.didNotTakePlace()
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

	/** What a table row is built from: a mentorship, or an accepted request with no mentorship row. */
	private record Pair(
			@Nullable UUID mentorshipId,
			@Nullable UUID requestId,
			MentorshipSource source,
			AppUser mentor,
			Organization organization,
			MentorshipStatus status,
			LocalDate startDate,
			long daysSinceContact,
			@Nullable String topic) {
	}
}
