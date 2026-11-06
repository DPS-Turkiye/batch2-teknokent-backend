package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.ChooseMentorProgramsRequest;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.MentorPoolService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Mentor onboarding's program step; it runs while the account is still pending, so only the token counts. */
@RestController
@RequestMapping("/api/onboarding/mentor-programs")
@RequiredArgsConstructor
@Tag(name = "Onboarding")
public class MentorProgramChoiceController {

	private final MentorPoolService mentorPoolService;

	@GetMapping
	public List<UUID> get() {
		return mentorPoolService.chosenPrograms(CurrentActor.firebaseUid());
	}

	@PutMapping
	public List<UUID> put(@Valid @RequestBody ChooseMentorProgramsRequest request) {
		return mentorPoolService.choosePrograms(CurrentActor.firebaseUid(), request.programIds());
	}
}
