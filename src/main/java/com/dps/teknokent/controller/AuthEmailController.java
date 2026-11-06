package com.dps.teknokent.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.dto.requests.PasswordResetRequest;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.service.AuthEmailService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class AuthEmailController {

	private final AuthEmailService authEmailService;

	/**
	 * Under /register so a new Firebase login without an account can reach it. The address comes from the verified
	 * token, so a caller can only mail themselves.
	 */
	@PostMapping("/register/verification-email")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void sendVerificationEmail(@AuthenticationPrincipal FirebaseIdentity identity) {
		if (identity == null || identity.email() == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A Firebase sign-in is required.");
		}
		authEmailService.sendVerification(identity.email());
	}

	@PostMapping("/password-reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void sendPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
		authEmailService.sendPasswordReset(request.email());
	}
}
