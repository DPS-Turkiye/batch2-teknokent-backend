package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateProgramEventRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.UpdateProgramDetailsRequest;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.dto.responses.ProgramEventResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.CoordinatorProgramService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator/programs")
@RequiredArgsConstructor
@Tag(name = "Coordinator programs")
public class CoordinatorProgramController {

	private final CoordinatorProgramService coordinatorProgramService;

	@GetMapping
	public List<CoordinatorProgramResponse> list(@RequestParam(required = false) UUID actorId) {
		return coordinatorProgramService.list(CurrentActor.resolve(actorId));
	}

	@GetMapping("/{id}")
	public CoordinatorProgramResponse.Detail detail(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorProgramService.detail(CurrentActor.resolve(actorId), id);
	}

	@PutMapping("/{id}")
	public CoordinatorProgramResponse.Detail updateDetails(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateProgramDetailsRequest request) {
		return coordinatorProgramService.updateDetails(CurrentActor.resolve(request.actorId()), id, request);
	}

	@PutMapping("/{id}/phases")
	public CoordinatorProgramResponse.Detail replacePhases(
			@PathVariable UUID id,
			@Valid @RequestBody ReplaceProgramPhasesRequest request) {
		return coordinatorProgramService.replacePhases(CurrentActor.resolve(request.actorId()), id, request);
	}

	@GetMapping("/{id}/events")
	public List<ProgramEventResponse> listEvents(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorProgramService.listEvents(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/{id}/events")
	@ResponseStatus(HttpStatus.CREATED)
	public ProgramEventResponse createEvent(
			@PathVariable UUID id,
			@Valid @RequestBody CreateProgramEventRequest request) {
		return coordinatorProgramService.createEvent(CurrentActor.resolve(request.actorId()), id, request);
	}

	@DeleteMapping("/{id}/events/{eventId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteEvent(
			@PathVariable UUID id,
			@PathVariable UUID eventId,
			@RequestParam(required = false) UUID actorId) {
		coordinatorProgramService.deleteEvent(CurrentActor.resolve(actorId), id, eventId);
	}
}
