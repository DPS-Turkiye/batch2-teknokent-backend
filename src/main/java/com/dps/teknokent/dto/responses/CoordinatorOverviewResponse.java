package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.CoordinatorAgendaKind;
import com.dps.teknokent.domain.ProgramLifecycle;

/** The coordinator home: headline metrics, this week's agenda and what needs a decision. */
public record CoordinatorOverviewResponse(
		List<ProgramOption> programs,
		Metrics metrics,
		NextMeeting nextMeeting,
		List<AgendaItem> agenda,
		Actions actions) {

	public record ProgramOption(UUID id, String name, ProgramLifecycle lifecycle) {
	}

	/**
	 * {@code slowMentors}: mentors holding a request unanswered for more than three days, the design's
	 * "katılım / iletişim problemi, mentor kaynaklı".
	 */
	public record Metrics(
			int activeMentorships,
			int mentoredTeams,
			int activeMentors,
			int staleMentorships,
			int overdueActions,
			int openBottlenecks,
			int slowMentors) {
	}

	public record NextMeeting(
			UUID meetingId,
			String title,
			Instant scheduledAt,
			Integer durationMinutes,
			String meetLink,
			int sequence,
			UUID mentorId,
			String mentorName,
			String mentorTitle,
			UUID organizationId,
			String organizationName,
			String founderName) {
	}

	/** {@code allDay} for action-item due dates, which have no time. */
	public record AgendaItem(
			CoordinatorAgendaKind kind,
			String title,
			String subtitle,
			Instant at,
			boolean allDay) {
	}

	public record Actions(
			List<PendingMentor> pendingMentors,
			List<AwaitingTeam> awaitingTeams,
			List<MissingNote> missingNotes) {
	}

	public record PendingMentor(UUID mentorId, String mentorName, UUID programId, String programName, int waitingDays) {
	}

	public record AwaitingTeam(
			UUID organizationId,
			String name,
			UUID programId,
			String programName,
			int waitingDays) {
	}

	public record MissingNote(UUID meetingId, Instant scheduledAt, String mentorName, String organizationName) {
	}
}
