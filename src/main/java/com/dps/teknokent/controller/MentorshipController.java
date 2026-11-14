package com.dps.teknokent.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateMentorshipGoalRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
import com.dps.teknokent.dto.requests.UpdateMentorshipGoalRequest;
import com.dps.teknokent.dto.requests.UpdateMentorshipRequest;
import com.dps.teknokent.dto.requests.UpsertMentorshipWeekCheckRequest;
import com.dps.teknokent.dto.responses.MentorshipGoalResponse;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.dto.responses.MentorshipWeekCheckResponse;
import com.dps.teknokent.security.CurrentActor;
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
	public List<MentorshipResponse> findAll(
			@RequestParam(required = false) UUID mentorId,
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
		if (activeOnly || mentorId != null || organizationId != null) {
			return mentorshipService.findActive(mentorId, organizationId);
		}
		return mentorshipService.findAll();
	}

	@GetMapping("/week-checks")
	public List<MentorshipWeekCheckResponse> listWeekChecks(
			@RequestParam UUID mentorId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
		return mentorshipService.listWeekChecks(mentorId, weekStart);
	}

	@GetMapping("/{id}")
	public MentorshipResponse findById(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return mentorshipService.findById(id, CurrentActor.resolveOptional(actorId));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MentorshipResponse create(@Valid @RequestBody CreateMentorshipRequest request) {
		return mentorshipService.create(request);
	}

	@PatchMapping("/{id}")
	public MentorshipResponse update(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody UpdateMentorshipRequest request) {
		return mentorshipService.update(id, CurrentActor.resolve(actorId), request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		mentorshipService.delete(id, CurrentActor.resolve(actorId));
	}

	@PostMapping("/{id}/goals")
	@ResponseStatus(HttpStatus.CREATED)
	public MentorshipGoalResponse addGoal(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody CreateMentorshipGoalRequest request) {
		return mentorshipService.addGoal(id, CurrentActor.resolve(actorId), request);
	}

	@PatchMapping("/{id}/goals/{goalId}")
	public MentorshipGoalResponse updateGoal(
			@PathVariable UUID id,
			@PathVariable UUID goalId,
			@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody UpdateMentorshipGoalRequest request) {
		return mentorshipService.updateGoal(id, goalId, CurrentActor.resolve(actorId), request);
	}

	@PutMapping("/{id}/week-checks")
	public MentorshipWeekCheckResponse upsertWeekCheck(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody UpsertMentorshipWeekCheckRequest request) {
		return mentorshipService.upsertWeekCheck(id, CurrentActor.resolve(actorId), request);
	}
}
