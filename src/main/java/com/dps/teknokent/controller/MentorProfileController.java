package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.service.MentorProfileService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/mentors")
@RequiredArgsConstructor
@Tag(name = "Mentors")
public class MentorProfileController {

	private final MentorProfileService mentorProfileService;

	@GetMapping
	public List<MentorProfileResponse> findAll() {
		return mentorProfileService.findAll();
	}

	@GetMapping("/{id}")
	public MentorProfileResponse findById(@PathVariable UUID id) {
		return mentorProfileService.findById(id);
	}
}
