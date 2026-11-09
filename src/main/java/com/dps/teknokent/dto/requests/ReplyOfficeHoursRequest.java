package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplyOfficeHoursRequest(@NotBlank @Size(max = 2000) String message) {
}
