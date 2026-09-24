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
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.CoordinatorAgendaKind;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProgramEventRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.UpdateProgramDetailsRequest;
import com.dps.teknokent.dto.responses.CoordinatorOverviewResponse;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.dto.responses.ProgramEventResponse;
import com.dps.teknokent.dto.responses.ProgramPhaseResponse;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCalendar;

import lombok.RequiredArgsConstructor;

/**
 * The coordinator's view of their programs: list cards, the overview tab, and the program's schedule
 * (details, phases, events). An admin sees and edits every program; a coordinator only their own.
 */
@Service
@RequiredArgsConstructor
public class CoordinatorProgramService {

	// Same definitions as the admin overview: PLANNED or ACTIVE means the team has a mentor.
	private static final Set<MentorshipStatus> MATCHED = EnumSet.of(MentorshipStatus.ACTIVE, MentorshipStatus.PLANNED);
	private static final List<BottleneckStatus> OPEN_BOTTLENECKS = List.of(BottleneckStatus.OPEN, BottleneckStatus.IN_PROGRESS);
	private static final List<ActionItemStatus> CLOSED_ACTIONS = List.of(ActionItemStatus.COMPLETED, ActionItemStatus.CANCELLED);
	private static final int RISKY_TOPIC_LIMIT = 3;
	private static final int AWAITING_TEAM_LIMIT = 5;
	private static final int UPCOMING_EVENT_LIMIT = 6;
	private static final int AGENDA_DAYS = 7;
	private static final int AGENDA_LIMIT = 20;
	private static final int SLOW_REPLY_DAYS = 3;
	private static final int MISSING_NOTE_DAYS = 14;

	private final DomainLookupService domainLookupService;
	private final AppUserRepository appUserRepository;
	private final ProgramRepository programRepository;
	private final ProgramPhaseRepository programPhaseRepository;
	private final ProgramEventRepository programEventRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final OrganizationRepository organizationRepository;
	private final MentorshipRepository mentorshipRepository;
	private final MeetingRepository meetingRepository;
	private final BottleneckRepository bottleneckRepository;
	private final ActionItemRepository actionItemRepository;
	private final MentorProfileRepository mentorProfileRepository;
	private final MeetingNoteRepository meetingNoteRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;

	enum HealthBucket {
		HEALTHY,
		FOLLOW_UP,
		RISKY
	}

	/**
	 * Risky once the team has gone {@code staleAfterDays} without meeting its mentor; needs follow-up once
	 * the target meeting interval has passed or the team has an open bottleneck or overdue action.
	 */
	static HealthBucket classify(long daysSinceContact, boolean hasOpenIssue, int meetingFrequencyDays, int staleAfterDays) {
		if (daysSinceContact >= staleAfterDays) {
			return HealthBucket.RISKY;
		}
		if (daysSinceContact >= meetingFrequencyDays || hasOpenIssue) {
			return HealthBucket.FOLLOW_UP;
		}
		return HealthBucket.HEALTHY;
	}

	@Transactional(readOnly = true)
	public List<CoordinatorProgramResponse> list(UUID actorId) {
		AppUser actor = requireCoordinatorOrAdmin(actorId);
		List<Program> programs = actor.getRole() == UserRole.ADMIN
				? programRepository.findAllByOrderByCreatedAtDesc()
				: programRepository.findByCreatedByOrderByCreatedAtDesc(actor.getId());
		Snapshot snapshot = snapshot(programs);
		return programs.stream().map(program -> summarize(program, snapshot)).toList();
	}

	@Transactional(readOnly = true)
	public CoordinatorProgramResponse.Detail detail(UUID actorId, UUID programId) {
		Program program = requireManagedProgram(actorId, programId);
		return detailOf(program);
	}

	@Transactional
	public CoordinatorProgramResponse.Detail updateDetails(
			UUID actorId,
			UUID programId,
			UpdateProgramDetailsRequest request) {
		Program program = requireManagedProgram(actorId, programId);
		requireOrderedDates(request.startDate(), request.endDate(), "The program cannot end before it starts.");
		program.setName(request.name().trim());
		program.setDescription(blankToNull(request.description()));
		program.setStartDate(request.startDate());
		program.setEndDate(request.endDate());
		if (request.meetingFrequencyDays() != null) {
			program.setMeetingFrequencyDays(request.meetingFrequencyDays());
		}
		if (request.staleAfterDays() != null) {
			program.setStaleAfterDays(request.staleAfterDays());
		}
		if (request.meetingNoteRequired() != null) {
			program.setMeetingNoteRequired(request.meetingNoteRequired());
		}
		if (request.autoMatchSuggestions() != null) {
			program.setAutoMatchSuggestions(request.autoMatchSuggestions());
		}
		programRepository.save(program);
		return detailOf(program);
	}

	@Transactional
	public CoordinatorProgramResponse.Detail replacePhases(
			UUID actorId,
			UUID programId,
			ReplaceProgramPhasesRequest request) {
		Program program = requireManagedProgram(actorId, programId);
		for (ReplaceProgramPhasesRequest.PhaseInput input : request.phases()) {
			requireOrderedDates(input.startDate(), input.endDate(), "A phase cannot end before it starts.");
		}
		programPhaseRepository.deleteByProgramId(programId);
		// Hibernate runs inserts before deletes at flush; without this, reusing a position would hit
		// uq_program_phase_position.
		programPhaseRepository.flush();
		List<ProgramPhase> phases = new ArrayList<>();
		int position = 0;
		for (ReplaceProgramPhasesRequest.PhaseInput input : request.phases()) {
			ProgramPhase phase = new ProgramPhase();
			phase.setId(UUID.randomUUID());
			phase.setProgramId(programId);
			phase.setName(input.name().trim());
			phase.setPosition(position++);
			phase.setStartDate(input.startDate());
			phase.setEndDate(input.endDate());
			phases.add(phase);
		}
		programPhaseRepository.saveAll(phases);
		return detailOf(program);
	}

	@Transactional(readOnly = true)
	public List<ProgramEventResponse> listEvents(UUID actorId, UUID programId) {
		requireManagedProgram(actorId, programId);
		return programEventRepository.findByProgramIdOrderByStartsAtAsc(programId).stream()
				.map(CoordinatorProgramService::toEvent)
				.toList();
	}

	@Transactional
	public ProgramEventResponse createEvent(UUID actorId, UUID programId, CreateProgramEventRequest request) {
		requireManagedProgram(actorId, programId);
		if (request.endsAt() != null && request.endsAt().isBefore(request.startsAt())) {
			throw new InvalidRequestException("An event cannot end before it starts.");
		}
		ProgramEvent event = new ProgramEvent();
		event.setId(UUID.randomUUID());
		event.setProgramId(programId);
		event.setTitle(request.title().trim());
		event.setKind(request.kind());
		event.setStartsAt(request.startsAt());
		event.setEndsAt(request.endsAt());
		event.setDescription(blankToNull(request.description()));
		event.setCreatedBy(actorId);
		event.setCreatedAt(Instant.now());
		return toEvent(programEventRepository.save(event));
	}

	@Transactional
	public void deleteEvent(UUID actorId, UUID programId, UUID eventId) {
		requireManagedProgram(actorId, programId);
		ProgramEvent event = programEventRepository.findByIdAndProgramId(eventId, programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program event not found: " + eventId));
		programEventRepository.delete(event);
	}

	/**
	 * The coordinator home for one program, or for all of the actor's running programs (completed ones are left
	 * out; an admin sees every program).
	 */
	@Transactional(readOnly = true)
	public CoordinatorOverviewResponse overview(UUID actorId, UUID programId) {
		AppUser actor = requireCoordinatorOrAdmin(actorId);
		List<Program> managed = actor.getRole() == UserRole.ADMIN
				? programRepository.findAllByOrderByCreatedAtDesc()
				: programRepository.findByCreatedByOrderByCreatedAtDesc(actor.getId());
		List<Program> scope = programId != null
				? List.of(requireManagedProgram(actorId, programId))
				: managed.stream().filter(program -> program.getStatus() != ProgramStatus.COMPLETED).toList();
		Snapshot snapshot = snapshot(scope);
		List<CoordinatorOverviewResponse.ProgramOption> options = managed.stream()
				.map(program -> new CoordinatorOverviewResponse.ProgramOption(
						program.getId(),
						program.getName(),
						ProgramCalendar.lifecycle(program.getStatus(), program.getStartDate(), snapshot.today())))
				.toList();
		Map<UUID, Program> programOf = new LinkedHashMap<>();
		for (Program program : scope) {
			for (ProgramStartup startup : snapshot.startups().getOrDefault(program.getId(), List.of())) {
				programOf.putIfAbsent(startup.getOrganizationId(), program);
			}
		}
		Set<UUID> organizationIds = programOf.keySet();
		List<Meeting> meetings = organizationIds.isEmpty()
				? List.of()
				: meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(organizationIds);
		return new CoordinatorOverviewResponse(
				options,
				overviewMetrics(programOf, snapshot),
				nextMeeting(meetings, snapshot.now()),
				agenda(scope, organizationIds, meetings, snapshot),
				new CoordinatorOverviewResponse.Actions(
						pendingMentors(scope, snapshot),
						awaitingTeamsAcross(scope, snapshot),
						missingNotes(meetings, snapshot.now())));
	}

	private CoordinatorOverviewResponse.Metrics overviewMetrics(Map<UUID, Program> programOf, Snapshot snapshot) {
		Set<UUID> matchedMentorships = new HashSet<>();
		Set<UUID> mentoredTeams = new HashSet<>();
		Set<UUID> activeMentors = new HashSet<>();
		int stale = 0;
		int bottlenecks = 0;
		for (Map.Entry<UUID, Program> entry : programOf.entrySet()) {
			UUID organizationId = entry.getKey();
			bottlenecks += snapshot.openBottlenecks().getOrDefault(organizationId, List.of()).size();
			for (Mentorship mentorship : mentorshipsOf(snapshot, organizationId)) {
				if (!MATCHED.contains(mentorship.getStatus())) {
					continue;
				}
				matchedMentorships.add(mentorship.getId());
				mentoredTeams.add(organizationId);
				activeMentors.add(mentorship.getMentor().getId());
				if (mentorship.getStatus() == MentorshipStatus.ACTIVE
						&& daysSinceContact(mentorship, snapshot) >= entry.getValue().getStaleAfterDays()) {
					stale++;
				}
			}
		}
		Set<UUID> organizationIds = programOf.keySet();
		int overdue = organizationIds.isEmpty()
				? 0
				: actionItemRepository
						.findByOrganizationIdInAndStatusNotInAndDueDateBefore(organizationIds, CLOSED_ACTIONS, snapshot.today())
						.size();
		Instant slowAfter = snapshot.now().minus(SLOW_REPLY_DAYS, ChronoUnit.DAYS);
		int slowMentors = organizationIds.isEmpty()
				? 0
				: (int) mentorshipRequestRepository
						.findByOrganizationIdInAndStatus(organizationIds, MentorshipRequestStatus.PENDING)
						.stream()
						.filter(request -> request.getCreatedAt() != null && request.getCreatedAt().isBefore(slowAfter))
						.map(request -> request.getMentor().getId())
						.distinct()
						.count();
		return new CoordinatorOverviewResponse.Metrics(
				matchedMentorships.size(),
				mentoredTeams.size(),
				activeMentors.size(),
				stale,
				overdue,
				bottlenecks,
				slowMentors);
	}

	private static CoordinatorOverviewResponse.NextMeeting nextMeeting(List<Meeting> meetings, Instant now) {
		Meeting next = meetings.stream()
				.filter(meeting -> meeting.getStatus() == MeetingStatus.SCHEDULED
						&& meeting.getScheduledAt() != null
						&& !meeting.getScheduledAt().isBefore(now)
						&& meeting.getMentor() != null)
				.min(Comparator.comparing(Meeting::getScheduledAt))
				.orElse(null);
		if (next == null) {
			return null;
		}
		UUID mentorId = next.getMentor().getId();
		UUID organizationId = next.getOrganization().getId();
		int sequence = (int) meetings.stream()
				.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED
						&& meeting.getScheduledAt() != null
						&& !meeting.getScheduledAt().isAfter(next.getScheduledAt())
						&& meeting.getMentor() != null
						&& meeting.getMentor().getId().equals(mentorId)
						&& meeting.getOrganization().getId().equals(organizationId))
				.count();
		AppUser founder = next.getOrganization().getFounder();
		return new CoordinatorOverviewResponse.NextMeeting(
				next.getId(),
				meetingTitle(next),
				next.getScheduledAt(),
				next.getDurationMinutes(),
				next.getMeetLink(),
				sequence,
				mentorId,
				next.getMentor().getFullName(),
				next.getMentor().getJobTitle(),
				organizationId,
				next.getOrganization().getName(),
				founder == null ? null : founder.getFullName());
	}

	private List<CoordinatorOverviewResponse.AgendaItem> agenda(
			List<Program> scope,
			Set<UUID> organizationIds,
			List<Meeting> meetings,
			Snapshot snapshot) {
		Instant from = snapshot.today().atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant();
		Instant to = snapshot.today().plusDays(AGENDA_DAYS).atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant();
		List<CoordinatorOverviewResponse.AgendaItem> items = new ArrayList<>();
		for (Meeting meeting : meetings) {
			Instant at = meeting.getScheduledAt();
			if (meeting.getStatus() != MeetingStatus.SCHEDULED || at == null || at.isBefore(from) || !at.isBefore(to)) {
				continue;
			}
			String mentor = meeting.getMentor() == null ? "Mentor" : meeting.getMentor().getFullName();
			items.add(new CoordinatorOverviewResponse.AgendaItem(
					CoordinatorAgendaKind.MEETING,
					meetingTitle(meeting),
					mentor + " ↔ " + meeting.getOrganization().getName(),
					at,
					false));
		}
		Map<UUID, String> programNames = scope.stream().collect(Collectors.toMap(Program::getId, Program::getName));
		if (!scope.isEmpty()) {
			for (ProgramEvent event : programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(
					programNames.keySet(), from, to.minusMillis(1))) {
				items.add(new CoordinatorOverviewResponse.AgendaItem(
						event.getKind() == ProgramEventKind.DEADLINE
								? CoordinatorAgendaKind.DEADLINE
								: CoordinatorAgendaKind.EVENT,
						event.getTitle(),
						programNames.get(event.getProgramId()),
						event.getStartsAt(),
						false));
			}
		}
		if (!organizationIds.isEmpty()) {
			for (ActionItem item : actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBetween(
					organizationIds, CLOSED_ACTIONS, snapshot.today(), snapshot.today().plusDays(AGENDA_DAYS - 1))) {
				items.add(new CoordinatorOverviewResponse.AgendaItem(
						CoordinatorAgendaKind.ACTION,
						item.getTitle(),
						item.getOrganization().getName(),
						item.getDueDate().atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant(),
						true));
			}
		}
		return items.stream()
				.sorted(Comparator.comparing(CoordinatorOverviewResponse.AgendaItem::at))
				.limit(AGENDA_LIMIT)
				.toList();
	}

	private List<CoordinatorOverviewResponse.PendingMentor> pendingMentors(List<Program> scope, Snapshot snapshot) {
		List<ProgramMentor> pending = new ArrayList<>();
		Map<UUID, Program> programById = new HashMap<>();
		for (Program program : scope) {
			programById.put(program.getId(), program);
			for (ProgramMentor membership : snapshot.mentors().getOrDefault(program.getId(), List.of())) {
				if (membership.getStatus() == ProgramMentorStatus.PENDING) {
					pending.add(membership);
				}
			}
		}
		if (pending.isEmpty()) {
			return List.of();
		}
		Map<UUID, AppUser> mentors = appUserRepository
				.findAllById(pending.stream().map(ProgramMentor::getMentorId).collect(Collectors.toSet()))
				.stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		return pending.stream()
				.map(membership -> {
					AppUser mentor = mentors.get(membership.getMentorId());
					Program program = programById.get(membership.getProgramId());
					LocalDate since = membership.getCreatedAt() == null
							? snapshot.today()
							: membership.getCreatedAt().atZone(MeetingSlotTimes.APP_ZONE).toLocalDate();
					return new CoordinatorOverviewResponse.PendingMentor(
							membership.getMentorId(),
							mentor == null ? "Mentor" : mentor.getFullName(),
							program.getId(),
							program.getName(),
							(int) Math.max(0, ChronoUnit.DAYS.between(since, snapshot.today())));
				})
				.sorted(Comparator.comparingInt(CoordinatorOverviewResponse.PendingMentor::waitingDays).reversed())
				.toList();
	}

	private List<CoordinatorOverviewResponse.AwaitingTeam> awaitingTeamsAcross(List<Program> scope, Snapshot snapshot) {
		List<CoordinatorOverviewResponse.AwaitingTeam> teams = new ArrayList<>();
		for (Program program : scope) {
			for (ProgramStartup startup : awaitingMatch(
					snapshot.startups().getOrDefault(program.getId(), List.of()), snapshot)) {
				teams.add(new CoordinatorOverviewResponse.AwaitingTeam(
						startup.getOrganizationId(),
						organizationName(snapshot, startup.getOrganizationId()),
						program.getId(),
						program.getName(),
						waitingDays(startup, snapshot.today())));
			}
		}
		teams.sort(Comparator.comparingInt(CoordinatorOverviewResponse.AwaitingTeam::waitingDays).reversed());
		return teams;
	}

	/** Meetings of the last two weeks that happened but left no written trace: no notes, outcomes or live note. */
	private List<CoordinatorOverviewResponse.MissingNote> missingNotes(List<Meeting> meetings, Instant now) {
		Instant since = now.minus(MISSING_NOTE_DAYS, ChronoUnit.DAYS);
		List<Meeting> recent = meetings.stream()
				.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED
						&& meeting.getScheduledAt() != null
						&& meeting.getScheduledAt().isBefore(now)
						&& !meeting.getScheduledAt().isBefore(since)
						&& isBlank(meeting.getNotes())
						&& isBlank(meeting.getOutcomes()))
				.toList();
		if (recent.isEmpty()) {
			return List.of();
		}
		Set<UUID> withLiveNotes = meetingNoteRepository
				.findByMeetingIdIn(recent.stream().map(Meeting::getId).toList())
				.stream()
				.filter(note -> !isBlank(note.getContent()))
				.map(note -> note.getMeeting().getId())
				.collect(Collectors.toSet());
		return recent.stream()
				.filter(meeting -> !withLiveNotes.contains(meeting.getId()))
				.map(meeting -> new CoordinatorOverviewResponse.MissingNote(
						meeting.getId(),
						meeting.getScheduledAt(),
						meeting.getMentor() == null ? "Mentor" : meeting.getMentor().getFullName(),
						meeting.getOrganization().getName()))
				.toList();
	}

	private static String meetingTitle(Meeting meeting) {
		return isBlank(meeting.getAgenda()) ? "Mentorluk görüşmesi" : meeting.getAgenda().trim();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private CoordinatorProgramResponse.Detail detailOf(Program program) {
		Snapshot snapshot = snapshot(List.of(program));
		CoordinatorProgramResponse summary = summarize(program, snapshot);
		List<ProgramStartup> startups = snapshot.startups().getOrDefault(program.getId(), List.of());
		List<ProgramStartup> awaiting = awaitingMatch(startups, snapshot);
		List<CoordinatorProgramResponse.AwaitingTeam> awaitingTeams = awaiting.stream()
				.map(startup -> new CoordinatorProgramResponse.AwaitingTeam(
						startup.getOrganizationId(),
						organizationName(snapshot, startup.getOrganizationId()),
						waitingDays(startup, snapshot.today())))
				.sorted(Comparator.comparingInt(CoordinatorProgramResponse.AwaitingTeam::waitingDays).reversed())
				.limit(AWAITING_TEAM_LIMIT)
				.toList();
		Instant startOfToday = snapshot.today().atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant();
		List<ProgramEventResponse> upcoming = programEventRepository
				.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(List.of(program.getId()), startOfToday)
				.stream()
				.limit(UPCOMING_EVENT_LIMIT)
				.map(CoordinatorProgramService::toEvent)
				.toList();
		return new CoordinatorProgramResponse.Detail(
				summary,
				new CoordinatorProgramResponse.Rules(
						program.getMeetingFrequencyDays(),
						program.getStaleAfterDays(),
						program.isMeetingNoteRequired(),
						program.isAutoMatchSuggestions()),
				health(program, startups, snapshot),
				awaitingTeams,
				upcoming,
				new CoordinatorProgramResponse.TeamDistribution(startups.size() - awaiting.size(), awaiting.size()),
				mentorDistribution(snapshot.mentors().getOrDefault(program.getId(), List.of())));
	}

	private CoordinatorProgramResponse summarize(Program program, Snapshot snapshot) {
		List<ProgramStartup> startups = snapshot.startups().getOrDefault(program.getId(), List.of());
		List<ProgramMentor> mentors = snapshot.mentors().getOrDefault(program.getId(), List.of());
		List<ProgramPhase> phases = snapshot.phases().getOrDefault(program.getId(), List.of());
		List<ProgramStartup> awaiting = awaitingMatch(startups, snapshot);
		int activeMentorships = 0;
		for (ProgramStartup startup : startups) {
			for (Mentorship mentorship : mentorshipsOf(snapshot, startup.getOrganizationId())) {
				if (MATCHED.contains(mentorship.getStatus())) {
					activeMentorships++;
				}
			}
		}
		Integer oldestWaiting = awaiting.stream()
				.map(startup -> waitingDays(startup, snapshot.today()))
				.max(Integer::compare)
				.orElse(null);
		ProgramPhase current = ProgramCalendar.currentPhase(phases, snapshot.today());
		AppUser coordinator = snapshot.coordinators().get(program.getCreatedBy());
		return new CoordinatorProgramResponse(
				program.getId(),
				program.getName(),
				program.getStatus(),
				ProgramCalendar.lifecycle(program.getStatus(), program.getStartDate(), snapshot.today()),
				program.getDescription(),
				program.getStartDate(),
				program.getEndDate(),
				program.getCreatedBy(),
				coordinator == null ? null : coordinator.getFullName(),
				startups.size(),
				(int) mentors.stream().filter(m -> m.getStatus() == ProgramMentorStatus.ACCEPTED).count(),
				(int) mentors.stream().filter(m -> m.getStatus() == ProgramMentorStatus.PENDING).count(),
				activeMentorships,
				health(program, startups, snapshot).risky(),
				awaiting.size(),
				oldestWaiting,
				current == null ? null : toPhase(current),
				phases.stream().map(CoordinatorProgramService::toPhase).toList(),
				ProgramCalendar.currentWeek(program.getStartDate(), program.getEndDate(), snapshot.today()),
				ProgramCalendar.totalWeeks(program.getStartDate(), program.getEndDate()));
	}

	private CoordinatorProgramResponse.Health health(Program program, List<ProgramStartup> startups, Snapshot snapshot) {
		int healthy = 0;
		int followUp = 0;
		int risky = 0;
		Map<String, Set<UUID>> topicTeams = new LinkedHashMap<>();
		for (ProgramStartup startup : startups) {
			UUID organizationId = startup.getOrganizationId();
			List<Bottleneck> openIssues = snapshot.openBottlenecks().getOrDefault(organizationId, List.of());
			boolean hasOpenIssue = !openIssues.isEmpty() || snapshot.overdueActionOrganizations().contains(organizationId);
			for (Mentorship mentorship : mentorshipsOf(snapshot, organizationId)) {
				if (mentorship.getStatus() != MentorshipStatus.ACTIVE) {
					continue;
				}
				HealthBucket bucket = classify(
						daysSinceContact(mentorship, snapshot),
						hasOpenIssue,
						program.getMeetingFrequencyDays(),
						program.getStaleAfterDays());
				switch (bucket) {
					case HEALTHY -> healthy++;
					case FOLLOW_UP -> followUp++;
					case RISKY -> risky++;
				}
				if (bucket != HealthBucket.HEALTHY) {
					for (Bottleneck issue : openIssues) {
						topicTeams.computeIfAbsent(issue.getTitle(), key -> new HashSet<>()).add(organizationId);
					}
				}
			}
		}
		List<CoordinatorProgramResponse.TopicCount> topics = topicTeams.entrySet().stream()
				.map(entry -> new CoordinatorProgramResponse.TopicCount(entry.getKey(), entry.getValue().size()))
				.sorted(Comparator.comparingInt(CoordinatorProgramResponse.TopicCount::teams).reversed())
				.limit(RISKY_TOPIC_LIMIT)
				.toList();
		return new CoordinatorProgramResponse.Health(healthy, followUp, risky, topics);
	}

	private CoordinatorProgramResponse.MentorDistribution mentorDistribution(List<ProgramMentor> memberships) {
		int pending = (int) memberships.stream().filter(m -> m.getStatus() == ProgramMentorStatus.PENDING).count();
		List<UUID> accepted = memberships.stream()
				.filter(m -> m.getStatus() == ProgramMentorStatus.ACCEPTED)
				.map(ProgramMentor::getMentorId)
				.toList();
		if (accepted.isEmpty()) {
			return new CoordinatorProgramResponse.MentorDistribution(0, 0, 0, pending);
		}
		// Load and capacity are global: a mentor full from another program is full here too.
		Map<UUID, Long> load = mentorshipRepository.countOrganizationsByMentor(accepted, MATCHED).stream()
				.collect(Collectors.toMap(MentorCount::getMentorId, MentorCount::getTotal));
		Map<UUID, Integer> capacity = new HashMap<>();
		for (MentorProfile profile : mentorProfileRepository.findAllById(accepted)) {
			if (profile.getMenteeCapacity() != null) {
				capacity.put(profile.getId(), profile.getMenteeCapacity());
			}
		}
		int withCapacity = 0;
		int full = 0;
		int idle = 0;
		for (UUID mentorId : accepted) {
			long teams = load.getOrDefault(mentorId, 0L);
			Integer limit = capacity.get(mentorId);
			if (limit != null && teams >= limit) {
				full++;
			} else if (teams == 0) {
				idle++;
			} else {
				withCapacity++;
			}
		}
		return new CoordinatorProgramResponse.MentorDistribution(withCapacity, full, idle, pending);
	}

	private List<ProgramStartup> awaitingMatch(List<ProgramStartup> startups, Snapshot snapshot) {
		return startups.stream()
				.filter(startup -> mentorshipsOf(snapshot, startup.getOrganizationId()).stream()
						.noneMatch(mentorship -> MATCHED.contains(mentorship.getStatus())))
				.toList();
	}

	private static long daysSinceContact(Mentorship mentorship, Snapshot snapshot) {
		Instant lastMeeting = snapshot.lastMeetingByPair().get(
				pairKey(mentorship.getMentor().getId(), mentorship.getOrganization().getId()));
		if (lastMeeting != null) {
			return Math.max(0, ChronoUnit.DAYS.between(lastMeeting, snapshot.now()));
		}
		LocalDate start = mentorship.getStartDate();
		return start == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(start, snapshot.today()));
	}

	private static int waitingDays(ProgramStartup startup, LocalDate today) {
		LocalDate joined = startup.getCreatedAt().atZone(MeetingSlotTimes.APP_ZONE).toLocalDate();
		return (int) Math.max(0, ChronoUnit.DAYS.between(joined, today));
	}

	private Snapshot snapshot(List<Program> programs) {
		Instant now = Instant.now();
		LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		if (programs.isEmpty()) {
			return new Snapshot(today, now, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
					Map.of(), Set.of());
		}
		List<UUID> programIds = programs.stream().map(Program::getId).toList();
		Map<UUID, AppUser> coordinators = appUserRepository
				.findAllById(programs.stream().map(Program::getCreatedBy).collect(Collectors.toSet()))
				.stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Map<UUID, List<ProgramPhase>> phases = programPhaseRepository.findByProgramIdInOrderByPositionAsc(programIds)
				.stream()
				.collect(Collectors.groupingBy(ProgramPhase::getProgramId));
		Map<UUID, List<ProgramStartup>> startups = programStartupRepository.findByProgramIdIn(programIds).stream()
				.collect(Collectors.groupingBy(ProgramStartup::getProgramId));
		Map<UUID, List<ProgramMentor>> mentors = programMentorRepository.findByProgramIdIn(programIds).stream()
				.collect(Collectors.groupingBy(ProgramMentor::getProgramId));
		Set<UUID> organizationIds = startups.values().stream()
				.flatMap(List::stream)
				.map(ProgramStartup::getOrganizationId)
				.collect(Collectors.toSet());
		if (organizationIds.isEmpty()) {
			return new Snapshot(today, now, coordinators, phases, startups, mentors, Map.of(), Map.of(), Map.of(),
					Map.of(), Set.of());
		}
		Map<UUID, Organization> organizations = organizationRepository.findAllById(organizationIds).stream()
				.collect(Collectors.toMap(Organization::getId, Function.identity()));
		Map<UUID, List<Mentorship>> mentorships = mentorshipRepository.findByOrganizationIdIn(organizationIds).stream()
				.collect(Collectors.groupingBy(mentorship -> mentorship.getOrganization().getId()));
		Map<String, Instant> lastMeeting = new HashMap<>();
		for (Meeting meeting : meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(organizationIds)) {
			if (meeting.getStatus() == MeetingStatus.CANCELLED || meeting.getScheduledAt() == null
					|| meeting.getScheduledAt().isAfter(now) || meeting.getMentor() == null) {
				continue;
			}
			lastMeeting.putIfAbsent(
					pairKey(meeting.getMentor().getId(), meeting.getOrganization().getId()),
					meeting.getScheduledAt());
		}
		Map<UUID, List<Bottleneck>> openBottlenecks = bottleneckRepository
				.findByOrganizationIdInAndStatusIn(organizationIds, OPEN_BOTTLENECKS)
				.stream()
				.collect(Collectors.groupingBy(bottleneck -> bottleneck.getOrganization().getId()));
		Set<UUID> overdue = actionItemRepository
				.findByOrganizationIdInAndStatusNotInAndDueDateBefore(organizationIds, CLOSED_ACTIONS, today)
				.stream()
				.map(item -> item.getOrganization().getId())
				.collect(Collectors.toSet());
		return new Snapshot(today, now, coordinators, phases, startups, mentors, organizations, mentorships,
				lastMeeting, openBottlenecks, overdue);
	}

	private AppUser requireCoordinatorOrAdmin(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.COORDINATOR && actor.getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only a coordinator can manage programs.");
		}
		return actor;
	}

	private Program requireManagedProgram(UUID actorId, UUID programId) {
		AppUser actor = requireCoordinatorOrAdmin(actorId);
		Program program = programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
		if (actor.getRole() == UserRole.COORDINATOR && !program.getCreatedBy().equals(actor.getId())) {
			throw new InvalidRequestException("Only the program coordinator can change this program.");
		}
		return program;
	}

	private static void requireOrderedDates(LocalDate start, LocalDate end, String message) {
		if (start != null && end != null && end.isBefore(start)) {
			throw new InvalidRequestException(message);
		}
	}

	private static List<Mentorship> mentorshipsOf(Snapshot snapshot, UUID organizationId) {
		return snapshot.mentorships().getOrDefault(organizationId, List.of());
	}

	private static String organizationName(Snapshot snapshot, UUID organizationId) {
		Organization organization = snapshot.organizations().get(organizationId);
		return organization == null ? "Takım" : organization.getName();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String pairKey(UUID mentorId, UUID organizationId) {
		return mentorId + ":" + organizationId;
	}

	private static ProgramPhaseResponse toPhase(ProgramPhase phase) {
		return new ProgramPhaseResponse(
				phase.getId(), phase.getName(), phase.getPosition(), phase.getStartDate(), phase.getEndDate());
	}

	private static ProgramEventResponse toEvent(ProgramEvent event) {
		return new ProgramEventResponse(
				event.getId(),
				event.getProgramId(),
				event.getTitle(),
				event.getKind(),
				event.getStartsAt(),
				event.getEndsAt(),
				event.getDescription());
	}

	/** Everything the summaries need, fetched once per call for a set of programs. */
	private record Snapshot(
			LocalDate today,
			Instant now,
			Map<UUID, AppUser> coordinators,
			Map<UUID, List<ProgramPhase>> phases,
			Map<UUID, List<ProgramStartup>> startups,
			Map<UUID, List<ProgramMentor>> mentors,
			Map<UUID, Organization> organizations,
			Map<UUID, List<Mentorship>> mentorships,
			Map<String, Instant> lastMeetingByPair,
			Map<UUID, List<Bottleneck>> openBottlenecks,
			Set<UUID> overdueActionOrganizations) {
	}
}
