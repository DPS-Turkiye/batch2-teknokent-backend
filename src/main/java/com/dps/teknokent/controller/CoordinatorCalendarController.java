package com.dps.teknokent.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.CoordinatorCalendarResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.CoordinatorCalendarService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator/calendar")
@RequiredArgsConstructor
@Tag(name = "Coordinator calendar")
public class CoordinatorCalendarController {

	private final CoordinatorCalendarService coordinatorCalendarService;

	/** {@code from} and {@code to} are inclusive days in the app's time zone. */
	@GetMapping
	public CoordinatorCalendarResponse calendar(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorCalendarService.calendar(CurrentActor.resolve(actorId), programId, from, to);
	}
}
