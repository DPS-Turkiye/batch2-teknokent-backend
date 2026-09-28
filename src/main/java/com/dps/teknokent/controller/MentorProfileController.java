package com.dps.teknokent.controller;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.security.CurrentActor;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateMentorAvailabilityRuleRequest;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilitySlotRequest;
import com.dps.teknokent.dto.requests.UpdateMentorProfileRequest;
import com.dps.teknokent.dto.requests.UpdateMentorAvailabilitySettingsRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySettingsResponse;
import com.dps.teknokent.dto.responses.MentorAvailabilityRuleResponse;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorRatingResponse;
import com.dps.teknokent.service.MentorProfileService;
import com.dps.teknokent.service.MentorRatingService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/mentors")
@RequiredArgsConstructor
@Tag(name = "Mentors")
public class MentorProfileController {

	private final MentorProfileService mentorProfileService;
	private final MentorRatingService mentorRatingService;

	@GetMapping
	public List<MentorProfileResponse> findAll() {
		return mentorProfileService.findAll();
	}

	@GetMapping("/{id}")
	public MentorProfileResponse findById(@PathVariable UUID id) {
		return mentorProfileService.findById(id);
	}

	@GetMapping("/{id}/rating")
	public MentorRatingResponse rating(@PathVariable UUID id) {
		return mentorRatingService.rating(id);
	}

	@PutMapping("/{id}")
	public MentorProfileResponse updateProfile(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateMentorProfileRequest request) {
		if (!CurrentActor.resolve(id).equals(id)) {
			throw new InvalidRequestException("You can only edit your own mentor profile");
		}
		return mentorProfileService.updateProfile(id, request);
	}

	@GetMapping("/{id}/availability-rules")
	public List<MentorAvailabilityRuleResponse> findAvailabilityRules(@PathVariable UUID id) {
		return mentorProfileService.findAvailabilityRules(id);
	}

	@GetMapping("/{id}/availability-settings")
	public MentorAvailabilitySettingsResponse findAvailabilitySettings(@PathVariable UUID id) {
		if (!CurrentActor.resolve(id).equals(id)) {
			throw new InvalidRequestException("You can only read your own availability settings");
		}
		return mentorProfileService.findAvailabilitySettings(id);
	}

	@PutMapping("/{id}/availability-settings")
	public MentorAvailabilitySettingsResponse updateAvailabilitySettings(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateMentorAvailabilitySettingsRequest request) {
		if (!CurrentActor.resolve(id).equals(id)) {
			throw new InvalidRequestException("You can only edit your own availability settings");
		}
		return mentorProfileService.updateAvailabilitySettings(id, request);
	}

	@PostMapping("/{id}/availability-rules")
	@ResponseStatus(HttpStatus.CREATED)
	public MentorAvailabilityRuleResponse createAvailabilityRule(
			@PathVariable UUID id,
			@Valid @RequestBody CreateMentorAvailabilityRuleRequest request) {
		return mentorProfileService.createAvailabilityRule(id, request);
	}

	@DeleteMapping("/{id}/availability-rules/{ruleId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAvailabilityRule(@PathVariable UUID id, @PathVariable UUID ruleId) {
		mentorProfileService.deleteAvailabilityRule(id, ruleId);
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
