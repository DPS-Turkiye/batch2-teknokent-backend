package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.UserAccountStatus;

import jakarta.validation.constraints.NotNull;

public record DecideAccountStatusRequest(
		@NotNull UUID actorId,
		@NotNull UserAccountStatus status) {
}
