package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.service.ProfileAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/profile-analyses")
@RequiredArgsConstructor
@Tag(name = "Profile analysis")
public class ProfileAnalysisController {

	private final ProfileAnalysisService profileAnalysisService;

	@GetMapping("/latest")
	public ProfileAnalysisResponse latest(@RequestParam(required = false) UUID organizationId) {
		return profileAnalysisService.findLatest(organizationId);
	}

	@GetMapping("/latest/confirmed")
	public ProfileAnalysisResponse latestConfirmedStartup(@RequestParam(required = false) UUID organizationId) {
		return profileAnalysisService.findLatestConfirmedStartup(organizationId);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public ProfileAnalysisResponse parse(
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(defaultValue = "mentee") String profileType,
			@RequestParam(defaultValue = "false") boolean consentAccepted,
			@RequestParam(defaultValue = "auto") String ocrMode,
			@RequestPart("file") MultipartFile file) {
		return profileAnalysisService.parse(organizationId, profileType, consentAccepted, ocrMode, file);
	}

	@PostMapping("/manual")
	@ResponseStatus(HttpStatus.CREATED)
	public ProfileAnalysisResponse createManual(
			@RequestParam(required = false) UUID organizationId,
			@jakarta.validation.Valid @RequestBody CreateManualProfileRequest request) {
		return profileAnalysisService.createManual(organizationId, request);
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
