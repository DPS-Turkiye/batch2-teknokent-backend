package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.CoordinatorMentorshipResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.CoordinatorMentorshipService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator/mentorships")
@RequiredArgsConstructor
@Tag(name = "Coordinator mentorships")
public class CoordinatorMentorshipController {

	private final CoordinatorMentorshipService coordinatorMentorshipService;

	@GetMapping
	public List<CoordinatorMentorshipResponse> list(
			@RequestParam(required = false) UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorMentorshipService.list(CurrentActor.resolve(actorId), programId);
	}

	@GetMapping("/{id}")
	public CoordinatorMentorshipResponse.Detail detail(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorMentorshipService.detail(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/{id}/remind")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remind(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		coordinatorMentorshipService.remind(CurrentActor.resolve(actorId), id);
	}
}
