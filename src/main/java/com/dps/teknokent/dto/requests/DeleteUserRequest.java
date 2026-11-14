package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code confirmEmail} is the address the admin typed to confirm; the server compares it with the account's. */
public record DeleteUserRequest(@NotBlank @Size(max = 255) String confirmEmail) {
}
