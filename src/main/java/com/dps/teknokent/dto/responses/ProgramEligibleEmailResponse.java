package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.ProgramEligibleEmail;

/** {@code registered}: an account with this address exists, so the coordinator can see who has signed up. */
public record ProgramEligibleEmailResponse(UUID id, String email, Instant addedAt, boolean registered) {

	public static ProgramEligibleEmailResponse of(ProgramEligibleEmail row, boolean registered) {
		return new ProgramEligibleEmailResponse(row.getId(), row.getEmail(), row.getCreatedAt(), registered);
	}
}
