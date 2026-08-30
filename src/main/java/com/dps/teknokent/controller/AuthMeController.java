package com.dps.teknokent.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AppUserService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class AuthMeController {

	private final AppUserService appUserService;

	@GetMapping("/me")
	public UserResponse me() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
			throw new InvalidRequestException("This endpoint requires a signed-in user account.");
		}
		return appUserService.findById(principal.id());
	}
}
