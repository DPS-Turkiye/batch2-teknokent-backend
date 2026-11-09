package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.SaveTrainingRequest;
import com.dps.teknokent.dto.responses.TrainingModuleResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.TrainingService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Reached through the Next.js proxy on the service credentials, with the caller taken from its session. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Training")
public class TrainingController {

	private final TrainingService trainingService;

	@GetMapping("/api/coordinator/programs/{programId}/training")
	public TrainingModuleResponse forEditor(@PathVariable UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return trainingService.forEditor(CurrentActor.resolve(actorId), programId);
	}

	@PutMapping("/api/coordinator/programs/{programId}/training")
	public TrainingModuleResponse save(@PathVariable UUID programId,
			@Valid @RequestBody SaveTrainingRequest request) {
		return trainingService.save(CurrentActor.resolve(request.actorId()), programId, request);
	}
}
