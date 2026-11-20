package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code confirmName} is the startup's name as the founder typed it to confirm; it must match exactly. */
public record DeleteOwnStartupRequest(@NotBlank @Size(max = 255) String confirmName) {
}
