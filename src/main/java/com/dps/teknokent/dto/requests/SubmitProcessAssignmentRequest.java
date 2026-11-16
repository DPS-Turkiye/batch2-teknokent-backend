package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * The file is uploaded through /api/documents first; this links it to the Ödev. Without {@code documentId} the Ödev
 * is only marked done, which a program Ödev that asks for no file allows.
 */
public record SubmitProcessAssignmentRequest(
		@NotNull UUID actorId,
		UUID documentId) {
}
