package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.ChangeFounderRequest;
import com.dps.teknokent.dto.responses.FounderChangeOptionsResponse;
import com.dps.teknokent.dto.responses.FounderChangeResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.FounderChangeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * An admin, or a coordinator of one of the startup's programs, makes another member its founder. Taken on the
 * caller's own verified sign-in only, never on the shared service credentials.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/founder")
@RequiredArgsConstructor
@Tag(name = "Team founders")
public class FounderChangeController {

	private final FounderChangeService founderChangeService;

	@GetMapping
	@Operation(summary = "The startup's founder and the members who may become founder")
	public FounderChangeOptionsResponse options(@PathVariable UUID organizationId) {
		return founderChangeService.options(CurrentActor.requireSignedIn(), organizationId);
	}

	@PostMapping
	@Operation(summary = "Make another current member the startup's founder")
	public FounderChangeResponse change(
			@PathVariable UUID organizationId,
			@Valid @RequestBody ChangeFounderRequest request) {
		return founderChangeService.change(CurrentActor.requireSignedIn(), organizationId, request.newFounderId());
	}
}
