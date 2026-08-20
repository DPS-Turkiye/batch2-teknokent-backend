package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateMentorAvailabilitySlotRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.service.MentorProfileService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

	@GetMapping("/{id}/availability")
	public List<MentorAvailabilitySlotResponse> findAvailability(@PathVariable UUID id) {
		return mentorProfileService.findAvailability(id);
	}

	@PostMapping("/{id}/availability")
	@ResponseStatus(HttpStatus.CREATED)
	public MentorAvailabilitySlotResponse createAvailabilitySlot(
			@PathVariable UUID id,
			@Valid @RequestBody CreateMentorAvailabilitySlotRequest request) {
		return mentorProfileService.createAvailabilitySlot(id, request);
	}

	@DeleteMapping("/{id}/availability/{slotId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAvailabilitySlot(@PathVariable UUID id, @PathVariable UUID slotId) {
		mentorProfileService.deleteAvailabilitySlot(id, slotId);
	}
}
