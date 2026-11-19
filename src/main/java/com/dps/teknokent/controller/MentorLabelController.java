package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.MentorLabelRequest;
import com.dps.teknokent.dto.requests.SetMentorLabelsRequest;
import com.dps.teknokent.dto.responses.MentorLabelResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.MentorLabelService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** A program's mentor labels: edited on Mentor Havuzu, read on mentor lists and profiles. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Mentor labels")
public class MentorLabelController {

	private final MentorLabelService mentorLabelService;

	@GetMapping("/coordinator/programs/{id}/mentor-labels")
	public MentorLabelResponse.Board board(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return mentorLabelService.board(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/coordinator/programs/{id}/mentor-labels")
	public MentorLabelResponse.Board create(@PathVariable UUID id, @Valid @RequestBody MentorLabelRequest request) {
		return mentorLabelService.create(CurrentActor.resolve(request.actorId()), id, request);
	}

	@PutMapping("/coordinator/programs/{id}/mentor-labels/{labelId}")
	public MentorLabelResponse.Board update(
			@PathVariable UUID id,
			@PathVariable UUID labelId,
			@Valid @RequestBody MentorLabelRequest request) {
		return mentorLabelService.update(CurrentActor.resolve(request.actorId()), id, labelId, request);
	}

	@DeleteMapping("/coordinator/programs/{id}/mentor-labels/{labelId}")
	public MentorLabelResponse.Board delete(
			@PathVariable UUID id,
			@PathVariable UUID labelId,
			@RequestParam(required = false) UUID actorId) {
		return mentorLabelService.delete(CurrentActor.resolve(actorId), id, labelId);
	}

	@PutMapping("/coordinator/programs/{id}/mentors/{mentorId}/labels")
	public MentorLabelResponse.Board setMentorLabels(
			@PathVariable UUID id,
			@PathVariable UUID mentorId,
			@Valid @RequestBody SetMentorLabelsRequest request) {
		return mentorLabelService.setMentorLabels(CurrentActor.resolve(request.actorId()), id, mentorId,
				request.labelIds());
	}

	/** The labels on the mentee's own program's mentors; empty for anyone else. */
	@GetMapping("/mentor-labels/directory")
	public MentorLabelResponse.Directory directory(@RequestParam(required = false) UUID actorId) {
		return mentorLabelService.directory(CurrentActor.resolveOptional(actorId));
	}

	/** One mentor's labels in the programs the caller may see. */
	@GetMapping("/mentor-labels/mentors/{mentorId}")
	public List<MentorLabelResponse.ProgramLabels> ofMentor(
			@PathVariable UUID mentorId,
			@RequestParam(required = false) UUID actorId) {
		return mentorLabelService.ofMentor(mentorId, CurrentActor.resolveOptional(actorId));
	}
}
