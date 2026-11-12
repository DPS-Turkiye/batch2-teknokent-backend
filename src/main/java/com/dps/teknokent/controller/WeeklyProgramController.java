package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.ReadWeeklyProgramRequest;
import com.dps.teknokent.dto.requests.SaveWeeklyProgramRequest;
import com.dps.teknokent.dto.responses.WeeklyProgramProposalResponse;
import com.dps.teknokent.dto.responses.WeeklyProgramSavedResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.WeeklyProgramService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator/programs/{programId}/weekly-program")
@RequiredArgsConstructor
@Tag(name = "Coordinator programs")
public class WeeklyProgramController {

	private final WeeklyProgramService weeklyProgramService;

	/** Reads an uploaded weekly program PDF into weeks and phases for review; saves nothing. */
	@PostMapping("/read")
	public WeeklyProgramProposalResponse read(
			@PathVariable UUID programId,
			@Valid @RequestBody ReadWeeklyProgramRequest request) {
		return weeklyProgramService.read(CurrentActor.resolve(request.actorId()), programId, request);
	}

	/** Saves the reviewed weeks, their phases and the file in one step. */
	@PutMapping
	public WeeklyProgramSavedResponse save(
			@PathVariable UUID programId,
			@Valid @RequestBody SaveWeeklyProgramRequest request) {
		return weeklyProgramService.save(CurrentActor.resolve(request.actorId()), programId, request);
	}
}
