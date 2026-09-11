package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateProcessAssignmentRequest;
import com.dps.teknokent.dto.requests.CreateStartupRoundRequest;
import com.dps.teknokent.dto.requests.SubmitProcessAssignmentRequest;
import com.dps.teknokent.dto.requests.UpdateStartupStageRequest;
import com.dps.teknokent.dto.responses.StartupProcessResponse;
import com.dps.teknokent.service.StartupProcessService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Every write answers with the whole process, so the Süreç tab re-renders from one payload. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/process")
@RequiredArgsConstructor
@Tag(name = "Startup Process")
public class StartupProcessController {

	private final StartupProcessService startupProcessService;

	@GetMapping
	public StartupProcessResponse get(@PathVariable UUID organizationId) {
		return startupProcessService.get(organizationId);
	}

	@PutMapping("/stage")
	public StartupProcessResponse updateStage(
			@PathVariable UUID organizationId,
			@Valid @RequestBody UpdateStartupStageRequest request) {
		return startupProcessService.updateStage(organizationId, request);
	}

	@PostMapping("/rounds")
	@ResponseStatus(HttpStatus.CREATED)
	public StartupProcessResponse createRound(
			@PathVariable UUID organizationId,
			@Valid @RequestBody CreateStartupRoundRequest request) {
		return startupProcessService.createRound(organizationId, request);
	}

	@PostMapping("/assignments")
	@ResponseStatus(HttpStatus.CREATED)
	public StartupProcessResponse createAssignment(
			@PathVariable UUID organizationId,
			@Valid @RequestBody CreateProcessAssignmentRequest request) {
		return startupProcessService.createAssignment(organizationId, request);
	}

	@PutMapping("/assignments/{assignmentId}/submission")
	public StartupProcessResponse submitAssignment(
			@PathVariable UUID organizationId,
			@PathVariable UUID assignmentId,
			@Valid @RequestBody SubmitProcessAssignmentRequest request) {
		return startupProcessService.submitAssignment(organizationId, assignmentId, request);
	}
}
