package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramLifecycle;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;

/**
 * A program as its coordinator sees it: the list card, and with {@link Detail} the overview tab. The detail's
 * {@code pendingApprovalCount} is the Onaylar tab's badge.
 */
public record CoordinatorProgramResponse(
		UUID id,
		String name,
		ProgramStatus status,
		ProgramLifecycle lifecycle,
		ProgramType type,
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
		List<ProgramPhaseResponse> phases,
		Integer currentWeek,
		Integer totalWeeks) {

	public record Detail(
			CoordinatorProgramResponse program,
			Rules rules,
			Health health,
			List<AwaitingTeam> awaitingTeams,
			List<ProgramEventResponse> upcomingEvents,
			TeamDistribution teams,
			MentorDistribution mentors,
			List<CoordinatorRef> coordinators,
			int missingNoteMeetings,
			Syllabus syllabus,
			int pendingApprovalCount) {
	}

	/** The syllabus file the phases were taken from; null when there is none. */
	public record Syllabus(UUID id, String filename, long sizeBytes, Instant uploadedAt) {
	}

	/** {@code owner} is the coordinator who created the program; the others were added to it. */
	public record CoordinatorRef(UUID id, String fullName, boolean owner) {
	}

	public record Rules(
			int meetingFrequencyDays,
			int staleAfterDays,
			boolean meetingNoteRequired,
			boolean autoMatchSuggestions,
			int defaultMentorCapacity,
			int communicationGapDays,
			int bottleneckFlagDays,
			String eligibleEmailDomain,
			boolean rosterOnly,
			boolean individualMentees) {
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
