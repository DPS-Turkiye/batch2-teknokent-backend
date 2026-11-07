package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.dps.teknokent.dto.requests.InviteMeetingGuestRequest;
import com.dps.teknokent.dto.requests.SubmitMeetingFeedbackRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.dto.responses.MeetingFeedbackResponse;
import com.dps.teknokent.security.CurrentActor;
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
	public List<MeetingResponse> findAll(
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID mentorId) {
		return meetingService.findAll(organizationId, mentorId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MeetingResponse create(@Valid @RequestBody CreateMeetingRequest request) {
		return meetingService.create(request);
	}

	@PostMapping("/{id}/feedback")
	@ResponseStatus(HttpStatus.CREATED)
	public MeetingFeedbackResponse submitFeedback(
			@PathVariable UUID id,
			@RequestParam UUID authorId,
			@Valid @RequestBody SubmitMeetingFeedbackRequest request) {
		return meetingService.submitFeedback(id, authorId, request);
	}

	/** The ids, among these meetings, that the author has already rated: one call for the home page's list. */
	@GetMapping("/feedback")
	public List<UUID> ratedMeetingIds(@RequestParam List<UUID> meetingIds, @RequestParam UUID authorId) {
		return meetingService.ratedMeetingIds(meetingIds, authorId);
	}

	/** 204 until the author has rated the meeting. */
	@GetMapping("/{id}/feedback")
	public ResponseEntity<MeetingFeedbackResponse> findFeedback(
			@PathVariable UUID id, @RequestParam UUID authorId) {
		return meetingService.findFeedback(id, authorId)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/**
	 * The mentor confirms the meeting was held; only confirmed meetings count in the program's sessions report.
	 * A bearer caller is always themselves; {@code mentorId} is read only on the proxy's service credentials.
	 */
	@PostMapping("/{id}/held")
	public MeetingResponse confirmHeld(@PathVariable UUID id, @RequestParam(required = false) UUID mentorId) {
		return meetingService.confirmHeld(id, CurrentActor.resolve(mentorId), true);
	}

	@DeleteMapping("/{id}/held")
	public MeetingResponse undoHeld(@PathVariable UUID id, @RequestParam(required = false) UUID mentorId) {
		return meetingService.confirmHeld(id, CurrentActor.resolve(mentorId), false);
	}

	@PatchMapping("/{id}")
	public MeetingResponse update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateMeetingRequest request) {
		return meetingService.update(id, request);
	}

	@GetMapping("/{id}/room")
	public List<MeetingResponse> findRoom(@PathVariable UUID id) {
		return meetingService.findRoom(id);
	}

	@PostMapping("/{id}/guests")
	@ResponseStatus(HttpStatus.CREATED)
	public MeetingResponse inviteGuest(
			@PathVariable UUID id,
			@RequestBody InviteMeetingGuestRequest request) {
		return meetingService.inviteGuest(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		meetingService.deleteRoom(id);
	}
}
