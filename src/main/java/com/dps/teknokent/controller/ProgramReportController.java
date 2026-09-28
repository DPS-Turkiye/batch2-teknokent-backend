package com.dps.teknokent.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.AdminOverviewResponse;
import com.dps.teknokent.dto.responses.ProgramMeetingRatingsResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.ProgramReportService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator/programs/{id}")
@RequiredArgsConstructor
@Tag(name = "Program report")
public class ProgramReportController {

	private final ProgramReportService programReportService;

	@GetMapping("/report")
	public AdminOverviewResponse report(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return programReportService.report(CurrentActor.resolve(actorId), id);
	}

	/** {@code from} is an inclusive day in the app's time zone; without it every held meeting counts. */
	@GetMapping("/meeting-ratings")
	public ProgramMeetingRatingsResponse meetingRatings(
			@PathVariable UUID id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) UUID actorId) {
		return programReportService.meetingRatings(CurrentActor.resolve(actorId), id, from);
	}
}
