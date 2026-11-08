package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.ActorRequest;
import com.dps.teknokent.dto.requests.SaveTrainingRequest;
import com.dps.teknokent.dto.requests.SubmitTrainingAttemptRequest;
import com.dps.teknokent.dto.responses.StudentTrainingResponse;
import com.dps.teknokent.dto.responses.TrainingAttemptResponse;
import com.dps.teknokent.dto.responses.TrainingModuleResponse;
import com.dps.teknokent.dto.responses.TrainingQuizResponse;
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

	@GetMapping("/api/training/peer")
	public StudentTrainingResponse forStudent(@RequestParam(required = false) UUID actorId) {
		return trainingService.forStudent(CurrentActor.resolve(actorId));
	}

	@PostMapping("/api/training/peer/video-watched")
	public StudentTrainingResponse videoWatched(@RequestBody(required = false) ActorRequest request) {
		return trainingService.videoWatched(CurrentActor.resolve(request == null ? null : request.actorId()));
	}

	@GetMapping("/api/training/peer/quiz")
	public TrainingQuizResponse quiz(@RequestParam(required = false) UUID actorId) {
		return trainingService.quiz(CurrentActor.resolve(actorId));
	}

	@PostMapping("/api/training/peer/attempts")
	public TrainingAttemptResponse submit(@Valid @RequestBody SubmitTrainingAttemptRequest request) {
		return trainingService.submit(CurrentActor.resolve(request.actorId()), request.version(), request.answers());
	}

	@PutMapping("/api/coordinator/programs/{programId}/training")
	public TrainingModuleResponse save(@PathVariable UUID programId,
			@Valid @RequestBody SaveTrainingRequest request) {
		return trainingService.save(CurrentActor.resolve(request.actorId()), programId, request);
	}
}
