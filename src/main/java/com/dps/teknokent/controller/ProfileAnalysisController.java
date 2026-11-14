package com.dps.teknokent.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.dps.teknokent.domain.ConsentAnswer;
import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.ProfileAnalysisService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/profile-analyses")
@RequiredArgsConstructor
@Tag(name = "Profile analysis")
public class ProfileAnalysisController {

	private final ProfileAnalysisService profileAnalysisService;

	@GetMapping("/latest")
	public ProfileAnalysisResponse latest(
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID appUserId) {
		return profileAnalysisService.findLatest(organizationId, appUserId);
	}

	@GetMapping("/latest/confirmed")
	public ProfileAnalysisResponse latestConfirmedStartup(@RequestParam(required = false) UUID organizationId) {
		return profileAnalysisService.findLatestConfirmedStartup(organizationId);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public ProfileAnalysisResponse parse(
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID appUserId,
			@RequestParam(defaultValue = "mentee") String profileType,
			@RequestParam(defaultValue = "false") boolean consentAccepted,
			@RequestParam(required = false) String consentTextVersion,
			@RequestParam(defaultValue = "auto") String ocrMode,
			@RequestPart("file") MultipartFile file) {
		UUID callerUserId = resolveCallerUserId();
		ConsentAnswer consent = new ConsentAnswer(consentAccepted, consentTextVersion);
		return callerUserId == null
				? profileAnalysisService.parse(
						organizationId, appUserId, profileType, consent, ocrMode, file)
				: profileAnalysisService.parseForCaller(
						callerUserId, appUserId, profileType, consent, ocrMode, file);
	}

	/**
	 * Null when the caller is the Next.js proxy on service Basic credentials,
	 * which still supplies organizationId from the session cookie. A browser
	 * posting directly carries a Firebase bearer, and its organization is
	 * resolved from the account rather than from what it asked for.
	 */
	private static UUID resolveCallerUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
			return principal.id();
		}
		return null;
	}

	/**
	 * Parse only — returns the structured profile and stores nothing. Used by the
	 * signup CV step, where the caller has no organization to scope a write to.
	 */
	@PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> parsePreview(
			@RequestParam(defaultValue = "individual_mentee") String profileType,
			@RequestParam(defaultValue = "false") boolean consentAccepted,
			@RequestParam(required = false) String consentTextVersion,
			@RequestParam(defaultValue = "auto") String ocrMode,
			@RequestPart("file") MultipartFile file) {
		return profileAnalysisService.parsePreview(
				profileType, new ConsentAnswer(consentAccepted, consentTextVersion), ocrMode, file);
	}

	/** Parse only, from a CV the signed-in user already uploaded. */
	@PostMapping("/preview/documents/{documentId}")
	public Map<String, Object> parseStoredCvPreview(
			@PathVariable UUID documentId,
			@RequestParam(defaultValue = "individual_mentee") String profileType,
			@RequestParam(defaultValue = "false") boolean consentAccepted,
			@RequestParam(required = false) String consentTextVersion,
			@RequestParam(defaultValue = "auto") String ocrMode) {
		return profileAnalysisService.parseStoredCvPreview(
				resolveCallerUserId(), documentId, profileType, new ConsentAnswer(consentAccepted, consentTextVersion),
				ocrMode);
	}

	@PostMapping("/manual")
	@ResponseStatus(HttpStatus.CREATED)
	public ProfileAnalysisResponse createManual(
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID appUserId,
			@jakarta.validation.Valid @RequestBody CreateManualProfileRequest request) {
		return profileAnalysisService.createManual(organizationId, appUserId, request);
	}

	@PostMapping("/{id}/recommendations")
	public JsonNode recommendations(@PathVariable UUID id) {
		return profileAnalysisService.recommendations(id);
	}

	@PatchMapping("/{id}/confirmation")
	public ProfileAnalysisResponse confirm(@PathVariable UUID id, @RequestBody String payload) {
		return profileAnalysisService.confirm(id, payload);
	}
}
