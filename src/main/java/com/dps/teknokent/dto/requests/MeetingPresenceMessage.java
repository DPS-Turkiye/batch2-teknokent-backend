package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MeetingPresenceMessage(
		@NotBlank @Size(max = 20000) String update) {
}
