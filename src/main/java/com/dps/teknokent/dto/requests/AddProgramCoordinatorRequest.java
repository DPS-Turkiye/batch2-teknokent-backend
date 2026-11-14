package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

/** An existing coordinator by {@code userId}, or an email to invite as in "Yeni Program Oluştur"; exactly one. */
public record AddProgramCoordinatorRequest(UUID actorId, UUID userId, @Valid NewCoordinatorInput invite) {

	@JsonIgnore
	@AssertTrue(message = "Send either userId or invite.")
	public boolean isOneCoordinator() {
		return (userId == null) != (invite == null);
	}
}
