package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;

public record MeetingResponse(
		UUID id,
		@Nullable UUID mentorshipRequestId,
		UUID organizationId,
		String organizationName,
		@Nullable UUID mentorId,
		@Nullable String mentorName,
		Instant scheduledAt,
		Integer durationMinutes,
		String agenda,
		String notes,
		String outcomes,
		String meetLink,
		MeetingStatus status,
		boolean transcribeRequested,
		Instant botDispatchedAt,
		Instant heldConfirmedAt) {

	public static MeetingResponse fromEntity(Meeting meeting) {
		return new MeetingResponse(
				meeting.getId(),
				meeting.getMentorshipRequest() == null ? null : meeting.getMentorshipRequest().getId(),
				meeting.getOrganization().getId(), Organization.visibleName(meeting.getOrganization()),
				meeting.getMentor() == null ? null : meeting.getMentor().getId(),
				meeting.getMentor() == null ? null : meeting.getMentor().getFullName(),
				meeting.getScheduledAt(), meeting.getDurationMinutes(), meeting.getAgenda(), meeting.getNotes(),
				meeting.getOutcomes(), meeting.getMeetLink(), meeting.getStatus(),
				meeting.isTranscribeRequested(), meeting.getBotDispatchedAt(), meeting.getHeldConfirmedAt());
	}
}
