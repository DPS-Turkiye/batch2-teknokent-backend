package com.dps.teknokent.dto.requests;

import com.dps.teknokent.domain.MeetingStatus;

import jakarta.validation.constraints.Size;

public record UpdateMeetingRequest(
		@Size(max = 10000) String notes,
		@Size(max = 10000) String outcomes,
		MeetingStatus status,
		Boolean transcriptBotDispatched,
		@Size(max = 2048) String meetLink) {
}
