package com.dps.teknokent.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.dto.responses.InstitutionCustomTokenResponse;
import com.dps.teknokent.dto.responses.InstitutionLoginCodeResponse;
import com.dps.teknokent.dto.responses.InstitutionLoginStartResponse;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.security.institution.DevInstitutionLoginProvider;
import com.dps.teknokent.service.InstitutionLoginService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

/**
 * Sign-in through an institution (ODTÜ). Called by the Next.js proxy on the service credentials, never by the
 * browser directly, so no path here is public; the browser only ever holds the state and the one-time code.
 */
@RestController
@RequestMapping("/api/auth/institution")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class InstitutionLoginController {

	private final InstitutionLoginService institutionLoginService;
	private final ObjectProvider<DevInstitutionLoginProvider> devStub;

	@PostMapping("/{provider}/start")
	public InstitutionLoginStartResponse start(@PathVariable String provider) {
		return institutionLoginService.start(provider);
	}

	@PostMapping("/{provider}/callback")
	public InstitutionLoginCodeResponse callback(@PathVariable String provider,
			@Valid @RequestBody CallbackRequest request) {
		return new InstitutionLoginCodeResponse(
				institutionLoginService.complete(provider, request.state(), request.params()));
	}

	@PostMapping("/exchange")
	public InstitutionCustomTokenResponse exchange(@Valid @RequestBody ExchangeRequest request) {
		return new InstitutionCustomTokenResponse(institutionLoginService.exchange(request.code()));
	}

	/** The personas the dev login page offers; a 404 whenever the stub is off. */
	@GetMapping("/" + DevInstitutionLoginProvider.ID + "/dev-personas")
	public List<DevPersonaResponse> devPersonas() {
		DevInstitutionLoginProvider stub = devStub.getIfAvailable();
		if (stub == null) {
			throw new ResourceNotFoundException("The dev login stub is off.");
		}
		return stub.personas().stream()
				.map(persona -> new DevPersonaResponse(persona.id(), persona.label(), persona.fullName(),
						persona.kinds()))
				.toList();
	}

	/** {@code params}: what the institution sent back (for the dev stub, {@code persona}). */
	public record CallbackRequest(@NotBlank String state, Map<String, String> params) {
	}

	public record ExchangeRequest(@NotBlank String code) {
	}

	public record DevPersonaResponse(String id, String label, String fullName, Set<IdentityKind> kinds) {
	}
}
