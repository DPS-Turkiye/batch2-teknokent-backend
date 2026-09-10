package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AcceptProposedSlotRequest(
		@NotNull UUID requesterId,
		@NotBlank @Size(max = 32) String selectedSlot) {
}
