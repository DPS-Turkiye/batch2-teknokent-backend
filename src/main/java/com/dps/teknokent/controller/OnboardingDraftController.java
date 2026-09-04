package com.dps.teknokent.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.UpsertOnboardingDraftRequest;
import com.dps.teknokent.dto.responses.OnboardingDraftResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.service.OnboardingDraftService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/onboarding/draft")
@RequiredArgsConstructor
@Tag(name = "Onboarding")
public class OnboardingDraftController {

	private final OnboardingDraftService onboardingDraftService;

	@GetMapping
	public ResponseEntity<OnboardingDraftResponse> get() {
		return onboardingDraftService.find(requireFirebaseUid())
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PutMapping
	public OnboardingDraftResponse put(@Valid @RequestBody UpsertOnboardingDraftRequest request) {
		return onboardingDraftService.upsert(requireFirebaseUid(), request);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete() {
		onboardingDraftService.delete(requireFirebaseUid());
	}

	private static String requireFirebaseUid() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof FirebaseIdentity identity) {
			return identity.uid();
		}
		if (authentication != null
				&& authentication.getPrincipal() instanceof AppUserPrincipal principal
				&& principal.firebaseUid() != null
				&& !principal.firebaseUid().isBlank()) {
			return principal.firebaseUid();
		}
		throw new InvalidRequestException("A Firebase session is required.");
	}
}
