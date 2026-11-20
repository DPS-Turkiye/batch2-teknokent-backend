package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code confirmation} is the word the person typed to confirm ("SİL"); the server checks it again. */
public record DeleteOwnAccountRequest(@NotBlank @Size(max = 32) String confirmation) {
}
