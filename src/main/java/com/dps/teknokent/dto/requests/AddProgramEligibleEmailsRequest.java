package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Addresses pasted by the coordinator, one per line; duplicates and ones already listed are skipped. */
public record AddProgramEligibleEmailsRequest(
		UUID actorId,
		@NotEmpty @Size(max = 3000) List<@NotBlank @Size(max = 320) String> emails) {
}
