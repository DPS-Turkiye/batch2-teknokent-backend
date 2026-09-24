package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramLifecycle;
import com.dps.teknokent.domain.ProgramStatus;

/** A program as its coordinator sees it: the list card, and with {@link Detail} the overview tab. */
public record CoordinatorProgramResponse(
		UUID id,
		String name,
		ProgramStatus status,
		ProgramLifecycle lifecycle,
		String description,
		LocalDate startDate,
		LocalDate endDate,
		UUID coordinatorId,
		String coordinatorName,
		int teamCount,
		int mentorCount,
		int pendingMentorCount,
		int activeMentorshipCount,
		int riskyMentorshipCount,
		int awaitingMatchCount,
		Integer oldestAwaitingMatchDays,
		ProgramPhaseResponse currentPhase,
		int phaseCount,
		Integer currentWeek,
		Integer totalWeeks) {

	public record Detail(
			CoordinatorProgramResponse program,
			Rules rules,
			List<ProgramPhaseResponse> phases,
			Health health,
			List<AwaitingTeam> awaitingTeams,
			List<ProgramEventResponse> upcomingEvents,
			TeamDistribution teams,
			MentorDistribution mentors) {
	}

	public record Rules(
			int meetingFrequencyDays,
			int staleAfterDays,
			boolean meetingNoteRequired,
			boolean autoMatchSuggestions) {
	}

	/** Active mentorships by how recently the team met its mentor; see CoordinatorProgramService#classify. */
	public record Health(int healthy, int followUp, int risky, List<TopicCount> riskyTopics) {
	}

	public record TopicCount(String title, int teams) {
	}

	public record AwaitingTeam(UUID organizationId, String name, int waitingDays) {
	}

	public record TeamDistribution(int mentored, int awaitingMatch) {
	}

	public record MentorDistribution(int withCapacity, int full, int idle, int pending) {
	}
}
