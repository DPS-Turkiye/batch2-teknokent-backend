package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Saved as a whole; an empty field clears it. */
public record UpdateOfficeHoursProfileRequest(
		boolean listed,
		@Size(max = 200) String defaultPlace,
		@Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "Online link must start with http:// or https://") String onlineLink,
		@Size(max = 320) @Email String contactEmail) {
}
