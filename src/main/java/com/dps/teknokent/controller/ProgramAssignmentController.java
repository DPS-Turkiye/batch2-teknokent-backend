package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateProgramAssignmentRequest;
import com.dps.teknokent.dto.responses.ProgramAssignmentResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.ProgramAssignmentService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Program › Ödevler: Ödevler given to every team of the program, and who handed each in. */
@RestController
@RequestMapping("/api/coordinator/programs/{programId}/assignments")
@RequiredArgsConstructor
@Tag(name = "Coordinator programs")
public class ProgramAssignmentController {

	private final ProgramAssignmentService programAssignmentService;

	@GetMapping
	public List<ProgramAssignmentResponse> list(
			@PathVariable UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return programAssignmentService.list(CurrentActor.resolve(actorId), programId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProgramAssignmentResponse create(
			@PathVariable UUID programId,
			@Valid @RequestBody CreateProgramAssignmentRequest request) {
		return programAssignmentService.create(CurrentActor.resolve(request.actorId()), programId, request);
	}
}
