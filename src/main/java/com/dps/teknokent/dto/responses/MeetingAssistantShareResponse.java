package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

public record MeetingAssistantShareResponse(
		UUID meetingId,
		@Nullable JsonNode transcript,
		@Nullable String transcriptUrl,
		@Nullable Instant transcriptPublishedAt,
		@Nullable JsonNode report,
		@Nullable String reportUrl,
		@Nullable Instant reportPublishedAt) {

	public static MeetingAssistantShareResponse empty(UUID meetingId) {
		return new MeetingAssistantShareResponse(meetingId, null, null, null, null, null, null);
	}
}
