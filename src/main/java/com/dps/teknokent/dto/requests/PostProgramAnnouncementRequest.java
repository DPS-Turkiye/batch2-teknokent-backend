package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.AnnouncementAudience;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code audience}: missing means {@link AnnouncementAudience#EVERYONE}, so older clients keep posting as before. */
public record PostProgramAnnouncementRequest(
		UUID actorId,
		@NotBlank @Size(max = 2000) String body,
		AnnouncementAudience audience) {

	public AnnouncementAudience audienceOrDefault() {
		return audience == null ? AnnouncementAudience.EVERYONE : audience;
	}
}
