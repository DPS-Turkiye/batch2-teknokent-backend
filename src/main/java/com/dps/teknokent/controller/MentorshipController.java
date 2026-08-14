package com.dps.teknokent.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.service.MentorshipService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/mentorships")
@RequiredArgsConstructor
@Tag(name = "Mentorships")
public class MentorshipController {

	private final MentorshipService mentorshipService;

	@GetMapping
	public List<MentorshipResponse> findAll() {
		return mentorshipService.findAll();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MentorshipResponse create(@Valid @RequestBody CreateMentorshipRequest request) {
		return mentorshipService.create(request);
	}
}
