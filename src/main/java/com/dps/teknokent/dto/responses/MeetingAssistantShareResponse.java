package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record MeetingAssistantShareResponse(
		UUID meetingId,
		JsonNode transcript,
		Instant transcriptPublishedAt,
		JsonNode report,
		Instant reportPublishedAt) {

	public static MeetingAssistantShareResponse empty(UUID meetingId) {
		return new MeetingAssistantShareResponse(meetingId, null, null, null, null);
	}
}
