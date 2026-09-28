package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.CoordinatorAgendaKind;
import com.dps.teknokent.domain.CoordinatorMentorshipHealth;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.dto.responses.CoordinatorCalendarResponse;
import com.dps.teknokent.dto.responses.CoordinatorMentorshipResponse;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.service.CoordinatorProgramService.Snapshot;
import com.dps.teknokent.util.MeetingSlotTimes;

import lombok.RequiredArgsConstructor;

/**
 * The coordinator's Takvim: every meeting of the teams in their programs plus the programs' own events and
 * deadlines, with a mentor's overlapping meetings flagged as conflicts.
 */
@Service
@RequiredArgsConstructor
public class CoordinatorCalendarService {

	static final int MAX_RANGE_DAYS = 62;
	private static final int DEFAULT_MEETING_MINUTES = 60;

	private final CoordinatorProgramService programService;
	private final CoordinatorMentorshipService mentorshipService;
	private final ProgramEventRepository programEventRepository;
	private final TranscriptBotCapacityService transcriptBotCapacity;

	@Transactional(readOnly = true)
	public CoordinatorCalendarResponse calendar(UUID actorId, UUID programId, LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw new InvalidRequestException("The range cannot end before it starts.");
		}
		if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
			throw new InvalidRequestException("The calendar range is limited to " + MAX_RANGE_DAYS + " days.");
		}
		List<Program> managed = programService.managedPrograms(programService.requireCoordinatorOrAdmin(actorId));
		List<Program> scope = programService.scopeOf(actorId, managed, programId);
		Snapshot snapshot = programService.snapshot(scope);
		Instant rangeStart = from.atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant();
		Instant rangeEnd = to.plusDays(1).atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant();

		Map<UUID, Program> programOf = new LinkedHashMap<>();
		for (Program program : scope) {
			for (ProgramStartup startup : snapshot.startups().getOrDefault(program.getId(), List.of())) {
				programOf.putIfAbsent(startup.getOrganizationId(), program);
			}
		}
		List<CoordinatorMentorshipResponse> mentorships = mentorshipService.list(actorId, programId);
		Map<String, CoordinatorMentorshipResponse> mentorshipOfPair = mentorships.stream()
				.collect(Collectors.toMap(
						row -> CoordinatorProgramService.pairKey(row.mentorId(), row.organizationId()),
						Function.identity(),
						(first, second) -> first));

		List<Meeting> meetings = new ArrayList<>();
		for (UUID organizationId : programOf.keySet()) {
			for (Meeting meeting : snapshot.meetingsByOrganization().getOrDefault(organizationId, List.of())) {
				Instant at = meeting.getScheduledAt();
				if (meeting.getStatus() != MeetingStatus.CANCELLED && at != null
						&& !at.isBefore(rangeStart) && at.isBefore(rangeEnd)) {
					meetings.add(meeting);
				}
			}
		}
		List<CoordinatorCalendarResponse.Conflict> conflicts = conflicts(meetings);
		Set<UUID> conflicting = conflicts.stream()
				.flatMap(conflict -> conflict.meetingIds().stream())
				.collect(Collectors.toSet());

		List<Meeting> recorded = transcriptBotCapacity.recordedAround(rangeStart, rangeEnd);
		List<CoordinatorCalendarResponse.Item> items = new ArrayList<>();
		for (Meeting meeting : meetings) {
			Program program = programOf.get(meeting.getOrganization().getId());
			UUID mentorId = meeting.getMentor() == null ? null : meeting.getMentor().getId();
			CoordinatorMentorshipResponse mentorship = mentorId == null
					? null
					: mentorshipOfPair.get(CoordinatorProgramService.pairKey(mentorId, meeting.getOrganization().getId()));
			items.add(new CoordinatorCalendarResponse.Item(
					meeting.getId(),
					CoordinatorAgendaKind.MEETING,
					CoordinatorProgramService.meetingTitle(meeting),
					meeting.getScheduledAt(),
					endOf(meeting),
					program.getId(),
					program.getName(),
					mentorship == null ? null : mentorship.id(),
					mentorId,
					meeting.getMentor() == null ? null : meeting.getMentor().getFullName(),
					meeting.getOrganization().getId(),
					meeting.getOrganization().getName(),
					mentorship == null ? null : mentorship.topic(),
					sequence(meeting, snapshot),
					meeting.getMeetLink(),
					null,
					conflicting.contains(meeting.getId()),
					TranscriptBotCapacityService.peak(recorded, meeting.getScheduledAt(), endOf(meeting), null)));
		}
		Map<UUID, String> programNames = scope.stream().collect(Collectors.toMap(Program::getId, Program::getName));
		List<ProgramEvent> events = scope.isEmpty()
				? List.of()
				: programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(
						programNames.keySet(), rangeStart, rangeEnd.minusMillis(1));
		for (ProgramEvent event : events) {
			items.add(new CoordinatorCalendarResponse.Item(
					event.getId(),
					event.getKind() == ProgramEventKind.DEADLINE ? CoordinatorAgendaKind.DEADLINE : CoordinatorAgendaKind.EVENT,
					event.getTitle(),
					event.getStartsAt(),
					event.getEndsAt(),
					event.getProgramId(),
					programNames.get(event.getProgramId()),
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					event.getDescription(),
					false,
					null));
		}
		items.sort(Comparator.comparing(CoordinatorCalendarResponse.Item::startsAt));

		int deadlines = (int) events.stream().filter(event -> event.getKind() == ProgramEventKind.DEADLINE).count();
		int unscheduledRisky = (int) mentorships.stream()
				.filter(row -> row.health() == CoordinatorMentorshipHealth.RISKY && row.nextMeetingAt() == null)
				.count();
		return new CoordinatorCalendarResponse(
				from,
				to,
				items,
				conflicts,
				new CoordinatorCalendarResponse.Summary(meetings.size(), events.size() - deadlines, deadlines, unscheduledRisky),
				transcriptBotCapacity.capacity());
	}

	/** Groups each mentor's meetings into runs that overlap in time; every run of two or more is a conflict. */
	static List<CoordinatorCalendarResponse.Conflict> conflicts(List<Meeting> meetings) {
		Map<UUID, List<Meeting>> byMentor = new HashMap<>();
		for (Meeting meeting : meetings) {
			if (meeting.getMentor() != null) {
				byMentor.computeIfAbsent(meeting.getMentor().getId(), key -> new ArrayList<>()).add(meeting);
			}
		}
		List<List<Meeting>> runs = new ArrayList<>();
		for (List<Meeting> mentorMeetings : byMentor.values()) {
			mentorMeetings.sort(Comparator.comparing(Meeting::getScheduledAt));
			List<Meeting> run = new ArrayList<>();
			Instant runEnd = null;
			for (Meeting meeting : mentorMeetings) {
				if (runEnd != null && meeting.getScheduledAt().isBefore(runEnd)) {
					run.add(meeting);
					runEnd = later(runEnd, endOf(meeting));
					continue;
				}
				runs.add(run);
				run = new ArrayList<>(List.of(meeting));
				runEnd = endOf(meeting);
			}
			runs.add(run);
		}
		return runs.stream()
				.filter(run -> run.size() > 1)
				.sorted(Comparator.comparing(run -> run.getFirst().getScheduledAt()))
				.map(run -> new CoordinatorCalendarResponse.Conflict(
						run.getFirst().getMentor().getId(),
						run.getFirst().getMentor().getFullName(),
						run.stream().map(Meeting::getId).toList()))
				.toList();
	}

	/** The meeting's number within its mentor–team pair, counting every earlier meeting that was not cancelled. */
	private static Integer sequence(Meeting meeting, Snapshot snapshot) {
		if (meeting.getMentor() == null) {
			return null;
		}
		UUID mentorId = meeting.getMentor().getId();
		return (int) snapshot.meetingsByOrganization().getOrDefault(meeting.getOrganization().getId(), List.of()).stream()
				.filter(other -> other.getStatus() != MeetingStatus.CANCELLED
						&& other.getScheduledAt() != null
						&& !other.getScheduledAt().isAfter(meeting.getScheduledAt())
						&& other.getMentor() != null
						&& other.getMentor().getId().equals(mentorId))
				.count();
	}

	private static Instant endOf(Meeting meeting) {
		int minutes = meeting.getDurationMinutes() == null ? DEFAULT_MEETING_MINUTES : meeting.getDurationMinutes();
		return meeting.getScheduledAt().plus(minutes, ChronoUnit.MINUTES);
	}

	private static Instant later(Instant a, Instant b) {
		return a.isAfter(b) ? a : b;
	}
}
