package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnsubscribeEmailRequest(@NotBlank @Size(max = 100) String token) {
}
