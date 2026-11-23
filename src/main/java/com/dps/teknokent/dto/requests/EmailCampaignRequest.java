package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.EmailAudience;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A draft: whom it goes to and what it says. The subject is one line, so it cannot add headers. */
public record EmailCampaignRequest(
		UUID actorId,
		@NotNull UUID programId,
		@NotNull EmailAudience audience,
		@NotBlank @Size(max = 200) @Pattern(regexp = "[^\\r\\n]*") String subject,
		@NotBlank @Size(max = 20000) String bodyHtml) {
}
