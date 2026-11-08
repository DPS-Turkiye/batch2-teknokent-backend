package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A revoke always says why; the holder and staff see the reason, nobody else. */
public record RevokeBadgeRequest(UUID actorId, @NotBlank @Size(max = 500) String reason) {
}
