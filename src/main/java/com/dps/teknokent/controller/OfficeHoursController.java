package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.UpdateOfficeHoursAvailabilityRequest;
import com.dps.teknokent.dto.responses.OfficeHoursAvailabilityResponse;
import com.dps.teknokent.dto.responses.OfficeHoursCourseResponse;
import com.dps.teknokent.service.OfficeHoursAvailabilityService;
import com.dps.teknokent.service.OfficeHoursCourseService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Office-hours data for people who came in through an LTI launch; only the frontend's server calls it. */
@RestController
@RequestMapping("/api/office-hours")
@RequiredArgsConstructor
@Tag(name = "Office hours")
public class OfficeHoursController {

	private final OfficeHoursCourseService courseService;
	private final OfficeHoursAvailabilityService availabilityService;

	@GetMapping("/users/{userId}/courses/{courseId}")
	public OfficeHoursCourseResponse course(@PathVariable UUID userId, @PathVariable UUID courseId) {
		return courseService.courseFor(userId, courseId);
	}

	/** An academic's own office hours, for every course they teach; 403 for someone who is only a student. */
	@GetMapping("/users/{userId}/availability")
	public OfficeHoursAvailabilityResponse availability(@PathVariable UUID userId) {
		return availabilityService.find(userId);
	}

	@PutMapping("/users/{userId}/availability")
	public OfficeHoursAvailabilityResponse saveAvailability(@PathVariable UUID userId,
			@Valid @RequestBody UpdateOfficeHoursAvailabilityRequest request) {
		return availabilityService.update(userId, request);
	}
}
