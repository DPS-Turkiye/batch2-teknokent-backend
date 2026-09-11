package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** The file is uploaded through /api/documents first; this links it to the Ödev. */
public record SubmitProcessAssignmentRequest(
		@NotNull UUID actorId,
		@NotNull UUID documentId) {
}
