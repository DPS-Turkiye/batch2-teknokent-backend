package com.dps.teknokent.dto.responses;

import java.util.UUID;

import com.dps.teknokent.domain.ProgramStatus;

/** A program the mentor has joined, with its status and the mentor's own role in it. */
public record MentorProgramResponse(UUID id, String name, ProgramStatus status, String role) {
}
