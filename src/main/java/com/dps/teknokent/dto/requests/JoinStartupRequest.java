package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinStartupRequest(UUID actorId, @NotBlank @Size(max = 64) String inviteCode) {
}
