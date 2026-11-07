package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.OfficeHoursCourseResponse;
import com.dps.teknokent.service.OfficeHoursCourseService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Office-hours data for people who came in through an LTI launch; only the frontend's server calls it. */
@RestController
@RequestMapping("/api/office-hours")
@RequiredArgsConstructor
@Tag(name = "Office hours")
public class OfficeHoursController {

	private final OfficeHoursCourseService courseService;

	@GetMapping("/users/{userId}/courses/{courseId}")
	public OfficeHoursCourseResponse course(@PathVariable UUID userId, @PathVariable UUID courseId) {
		return courseService.courseFor(userId, courseId);
	}
}
