package com.dps.teknokent.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.RegisterFounderRequest;
import com.dps.teknokent.dto.requests.RegisterMemberRequest;
import com.dps.teknokent.dto.requests.RegisterMentorRequest;
import com.dps.teknokent.dto.responses.UserResponse;
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
