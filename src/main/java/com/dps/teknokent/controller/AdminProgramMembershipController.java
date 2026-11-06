package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.AdminProgramMembershipService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/programs/{programId}")
@RequiredArgsConstructor
@Tag(name = "Admin programs")
public class AdminProgramMembershipController {

	private final AdminProgramMembershipService adminProgramMembershipService;

	@PostMapping("/mentors/{mentorId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void assignMentor(
			@PathVariable UUID programId,
			@PathVariable UUID mentorId,
			@RequestParam(required = false) UUID actorId) {
		adminProgramMembershipService.assignMentor(CurrentActor.resolve(actorId), programId, mentorId);
	}

	@PostMapping("/startups/{organizationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void assignStartup(
			@PathVariable UUID programId,
			@PathVariable UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		adminProgramMembershipService.assignStartup(CurrentActor.resolve(actorId), programId, organizationId);
	}
}
