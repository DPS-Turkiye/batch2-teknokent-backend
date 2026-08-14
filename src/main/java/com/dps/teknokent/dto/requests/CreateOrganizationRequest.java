package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
		@NotBlank @Size(max = 255) String name,
		@Size(max = 5000) String description) {
}
