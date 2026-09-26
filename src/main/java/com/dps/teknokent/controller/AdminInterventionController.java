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

import com.dps.teknokent.dto.requests.OpenInterventionRequest;
import com.dps.teknokent.dto.requests.ResolveInterventionRequest;
import com.dps.teknokent.dto.responses.AdminInterventionResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.AdminInterventionService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/interventions")
@RequiredArgsConstructor
@Tag(name = "Admin interventions")
public class AdminInterventionController {

	private final AdminInterventionService adminInterventionService;

	@GetMapping
	public List<AdminInterventionResponse> list(@RequestParam(required = false) UUID actorId) {
		return adminInterventionService.list(CurrentActor.resolve(actorId));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AdminInterventionResponse open(@Valid @RequestBody OpenInterventionRequest request) {
		return adminInterventionService.open(CurrentActor.resolve(request.actorId()), request);
	}

	@PutMapping("/{id}/resolve")
	public AdminInterventionResponse resolve(
			@PathVariable UUID id,
			@Valid @RequestBody ResolveInterventionRequest request) {
		return adminInterventionService.resolve(CurrentActor.resolve(request.actorId()), id);
	}
}
