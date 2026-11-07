package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.dto.requests.RegisterFounderRequest;
import com.dps.teknokent.dto.requests.RegisterMemberRequest;
import com.dps.teknokent.dto.requests.RegisterMentorRequest;
import com.dps.teknokent.dto.responses.ProgramEligibilityResponse;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.service.ProgramEligibilityService;
import com.dps.teknokent.service.RegistrationService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth/register")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class AuthRegistrationController {

	private final RegistrationService registrationService;
	private final ProgramEligibilityService programEligibilityService;

	/**
	 * Asked by sign-up when a program is picked. The address comes from the verified token, so a caller learns
	 * only about their own address, never whether someone else is on a program's list.
	 */
	@GetMapping("/program-eligibility")
	public ProgramEligibilityResponse programEligibility(
			@AuthenticationPrincipal FirebaseIdentity identity,
			@RequestParam UUID programId) {
		if (identity == null || identity.email() == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A Firebase sign-in is required.");
		}
		return programEligibilityService.check(programId, identity.email());
	}

	@PostMapping("/founder")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse registerFounder(@Valid @RequestBody RegisterFounderRequest request) {
		return registrationService.registerFounder(request);
	}

	@PostMapping("/mentor")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse registerMentor(@Valid @RequestBody RegisterMentorRequest request) {
		return registrationService.registerMentor(request);
	}

	@PostMapping("/member")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse registerMember(@Valid @RequestBody RegisterMemberRequest request) {
		return registrationService.registerMember(request);
	}
}
