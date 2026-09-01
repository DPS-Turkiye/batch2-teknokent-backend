package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;

public record PublishMeetingAssistantShareRequest(
		UUID actorId,
		@NotBlank String kind,
		JsonNode transcript,
		JsonNode report) {
}
