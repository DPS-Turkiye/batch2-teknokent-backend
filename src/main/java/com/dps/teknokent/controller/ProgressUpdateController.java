package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateProgressUpdateRequest;
import com.dps.teknokent.dto.responses.ProgressUpdateResponse;
import com.dps.teknokent.service.ProgressUpdateService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/organizations/{organizationId}/progress")
@RequiredArgsConstructor
@Tag(name = "Progress Updates")
public class ProgressUpdateController {

	private final ProgressUpdateService progressUpdateService;

	@GetMapping
	public List<ProgressUpdateResponse> findByOrganization(@PathVariable UUID organizationId) {
		return progressUpdateService.findByOrganization(organizationId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProgressUpdateResponse create(
			@PathVariable UUID organizationId,
			@Valid @RequestBody CreateProgressUpdateRequest request) {
		return progressUpdateService.create(organizationId, request);
	}
}
