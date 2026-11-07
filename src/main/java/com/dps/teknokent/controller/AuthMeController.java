package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.SaveLanguageRequest;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AppUserService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class AuthMeController {

	private final AppUserService appUserService;

	@GetMapping("/me")
	public UserResponse me() {
		return appUserService.findSignedIn(signedInUserId());
	}

	/** The language this user's notifications and e-mails are written in. */
	@PutMapping("/me/language")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveLanguage(@Valid @RequestBody SaveLanguageRequest request) {
		appUserService.saveLanguage(signedInUserId(), request.language());
	}

	private static UUID signedInUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
			throw new InvalidRequestException("This endpoint requires a signed-in user account.");
		}
		return principal.id();
	}
}
