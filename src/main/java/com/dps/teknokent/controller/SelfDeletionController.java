package com.dps.teknokent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.DeleteOwnAccountRequest;
import com.dps.teknokent.dto.requests.DeleteOwnStartupRequest;
import com.dps.teknokent.dto.responses.SelfDeletionOptionsResponse;
import com.dps.teknokent.dto.responses.StartupDeletionResponse;
import com.dps.teknokent.dto.responses.UserDeletionResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.SelfDeletionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Deleting one's own account or startup. The caller is the verified token's owner only (see
 * {@link CurrentActor#requireSignedIn}); POST rather than DELETE because the confirmation travels in the body, which
 * proxies may drop from a DELETE.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "Self deletion")
public class SelfDeletionController {

	private final SelfDeletionService selfDeletionService;

	@GetMapping("/deletion")
	@Operation(summary = "What the caller may delete themselves")
	public SelfDeletionOptionsResponse options() {
		return selfDeletionService.options(CurrentActor.requireSignedIn());
	}

	@PostMapping("/delete")
	@Operation(summary = "Delete one's own account for good; shared history stays, anonymized")
	public UserDeletionResponse deleteAccount(@Valid @RequestBody DeleteOwnAccountRequest request) {
		return selfDeletionService.deleteAccount(CurrentActor.requireSignedIn(), CurrentActor.signedInAt(),
				request.confirmation());
	}

	@PostMapping("/startup/delete")
	@Operation(summary = "The founder deletes their startup; members stay, shared history stays, anonymized")
	public StartupDeletionResponse deleteStartup(@Valid @RequestBody DeleteOwnStartupRequest request) {
		return selfDeletionService.deleteStartup(CurrentActor.requireSignedIn(), CurrentActor.signedInAt(),
				request.confirmName());
	}
}
