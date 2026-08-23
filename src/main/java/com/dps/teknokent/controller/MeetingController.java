package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.service.MeetingService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
@Tag(name = "Meetings")
public class MeetingController {

	private final MeetingService meetingService;

	@GetMapping
	public List<MeetingResponse> findAll(@RequestParam(required = false) UUID organizationId) {
		return meetingService.findAll(organizationId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MeetingResponse create(@Valid @RequestBody CreateMeetingRequest request) {
		return meetingService.create(request);
	}

	@PatchMapping("/{id}")
	public MeetingResponse update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateMeetingRequest request) {
		return meetingService.update(id, request);
	}
}
