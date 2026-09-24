package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.CoordinatorOverviewResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.CoordinatorProgramService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator")
@RequiredArgsConstructor
@Tag(name = "Coordinator programs")
public class CoordinatorOverviewController {

	private final CoordinatorProgramService coordinatorProgramService;

	@GetMapping("/overview")
	public CoordinatorOverviewResponse overview(
			@RequestParam(required = false) UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorProgramService.overview(CurrentActor.resolve(actorId), programId);
	}
}
