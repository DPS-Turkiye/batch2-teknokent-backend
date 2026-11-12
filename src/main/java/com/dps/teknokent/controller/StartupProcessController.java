package com.dps.teknokent.controller;

import com.dps.teknokent.security.CurrentActor;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
	public StartupProcessResponse get(
			@PathVariable UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return startupProcessService.get(organizationId, CurrentActor.resolve(actorId));
	}

	@PutMapping("/stage")
	public StartupProcessResponse updateStage(
			@PathVariable UUID organizationId,
			@Valid @RequestBody UpdateStartupStageRequest request) {
		return startupProcessService.updateStage(organizationId,
				new UpdateStartupStageRequest(CurrentActor.resolve(request.actorId()), request.stage()));
	}

	@PostMapping("/rounds")
	@ResponseStatus(HttpStatus.CREATED)
	public StartupProcessResponse createRound(
			@PathVariable UUID organizationId,
			@Valid @RequestBody CreateStartupRoundRequest request) {
		return startupProcessService.createRound(organizationId, new CreateStartupRoundRequest(
				CurrentActor.resolve(request.actorId()), request.title(), request.name(), request.stage(),
				request.startsOn(), request.endsOn(), request.phaseId()));
	}

	@PostMapping("/assignments")
	@ResponseStatus(HttpStatus.CREATED)
	public StartupProcessResponse createAssignment(
			@PathVariable UUID organizationId,
			@Valid @RequestBody CreateProcessAssignmentRequest request) {
		return startupProcessService.createAssignment(organizationId, new CreateProcessAssignmentRequest(
				CurrentActor.resolve(request.actorId()), request.roundId(), request.stage(), request.title(),
				request.description(), request.dueDate(), request.phaseId()));
	}

	@PutMapping("/assignments/{assignmentId}/submission")
	public StartupProcessResponse submitAssignment(
			@PathVariable UUID organizationId,
			@PathVariable UUID assignmentId,
			@Valid @RequestBody SubmitProcessAssignmentRequest request) {
		return startupProcessService.submitAssignment(organizationId, assignmentId,
				new SubmitProcessAssignmentRequest(CurrentActor.resolve(request.actorId()), request.documentId()));
	}
}
