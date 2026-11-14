package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.MeetingAssistantShareKind;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record PublishMeetingAssistantShareRequest(
		UUID actorId,
		@NotNull MeetingAssistantShareKind kind,
		JsonNode transcript,
		JsonNode report) {
}
