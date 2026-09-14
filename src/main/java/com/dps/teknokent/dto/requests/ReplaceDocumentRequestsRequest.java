package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplaceDocumentRequestsRequest(
		@NotNull UUID actorId,
		List<@NotBlank @Size(max = 500) String> titles) {
}
