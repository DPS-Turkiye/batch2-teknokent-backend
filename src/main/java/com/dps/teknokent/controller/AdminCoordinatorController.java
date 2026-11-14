package com.dps.teknokent.controller;

import java.util.List;
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

import com.dps.teknokent.dto.requests.AddCoordinatorRequest;
import com.dps.teknokent.dto.requests.SetCoordinatorAccessRequest;
import com.dps.teknokent.dto.responses.CoordinatorAccountResponse;
import com.dps.teknokent.dto.responses.CoordinatorCandidateResponse;
import com.dps.teknokent.dto.responses.CoordinatorSearchResult;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.AdminCoordinatorService;
import com.dps.teknokent.service.CoordinatorSearchService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/coordinators")
@RequiredArgsConstructor
@Tag(name = "Admin coordinators")
public class AdminCoordinatorController {

	private final AdminCoordinatorService adminCoordinatorService;
	private final CoordinatorSearchService coordinatorSearchService;

	@GetMapping("/candidate")
	public CoordinatorCandidateResponse candidate(
			@RequestParam String email,
			@RequestParam(required = false) UUID actorId) {
		return adminCoordinatorService.candidate(CurrentActor.resolve(actorId), email);
	}

	/** Approved coordinators whose email starts with {@code q} (at least 3 characters, at most 10 results). */
	@GetMapping("/search")
	public List<CoordinatorSearchResult> search(
			@RequestParam String q,
			@RequestParam(required = false) UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorSearchService.search(CurrentActor.resolve(actorId), q, programId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CoordinatorAccountResponse add(@Valid @RequestBody AddCoordinatorRequest request) {
		return adminCoordinatorService.add(CurrentActor.resolve(request.actorId()), request);
	}

	@PutMapping("/{id}/access")
	public CoordinatorAccountResponse setAccess(
			@PathVariable UUID id,
			@Valid @RequestBody SetCoordinatorAccessRequest request) {
		return adminCoordinatorService.setAccess(CurrentActor.resolve(request.actorId()), id, request.active());
	}

	@PostMapping("/{id}/password-setup")
	public CoordinatorAccountResponse resendPasswordSetup(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return adminCoordinatorService.resendPasswordSetup(CurrentActor.resolve(actorId), id);
	}
}
