package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.AcceptProposedSlotRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.DecideMentorshipRequest;
import com.dps.teknokent.dto.requests.LogMeetingRequest;
import com.dps.teknokent.dto.requests.PostMentorMessageRequest;
import com.dps.teknokent.dto.requests.ReplyToMentorshipRequest;

import com.dps.teknokent.dto.requests.ScheduleMentorshipRequest;
import com.dps.teknokent.dto.requests.WithdrawMentorshipRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.service.MeetingService;
import com.dps.teknokent.service.MentorshipRequestService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/mentorship-requests")
@RequiredArgsConstructor
@Tag(name = "Mentorship Requests")
public class MentorshipRequestController {

	private final MentorshipRequestService mentorshipRequestService;
	private final MeetingService meetingService;

	@GetMapping
	public List<MentorshipRequestResponse> findAll(
			@RequestParam(required = false) UUID requesterId,
			@RequestParam(required = false) UUID mentorId,
			@RequestParam(required = false) UUID organizationId) {
		return mentorshipRequestService.findAll(requesterId, mentorId, organizationId);
	}

	@GetMapping("/{id}")
	public MentorshipRequestResponse findById(@PathVariable UUID id) {
		return mentorshipRequestService.findById(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MentorshipRequestResponse create(@Valid @RequestBody CreateMentorshipRequestSubmission request) {
		return mentorshipRequestService.create(request);
	}

	@PatchMapping("/{id}/decision")
	public MentorshipRequestResponse decide(
			@PathVariable UUID id,
			@Valid @RequestBody DecideMentorshipRequest decision) {
		return mentorshipRequestService.decide(id, decision);
	}

	@PatchMapping("/{id}/accept-proposal")
	public MentorshipRequestResponse acceptProposedSlot(
			@PathVariable UUID id,
			@Valid @RequestBody AcceptProposedSlotRequest acceptance) {
		return mentorshipRequestService.acceptProposedSlot(id, acceptance);
	}

	@PostMapping("/{id}/messages")
	public MentorshipRequestResponse reply(
			@PathVariable UUID id,
			@Valid @RequestBody ReplyToMentorshipRequest reply) {
		return mentorshipRequestService.reply(id, reply);
	}

	@PostMapping("/{id}/mentor-messages")
	public MentorshipRequestResponse postMentorMessage(
			@PathVariable UUID id,
			@Valid @RequestBody PostMentorMessageRequest request) {
		return mentorshipRequestService.postMentorMessage(id, request);
	}

	@PatchMapping("/{id}/withdrawal")
	public MentorshipRequestResponse withdraw(
			@PathVariable UUID id,
			@Valid @RequestBody WithdrawMentorshipRequest withdrawal) {
		return mentorshipRequestService.withdraw(id, withdrawal);
	}

	@GetMapping("/{id}/availability")
	public List<MentorAvailabilitySlotResponse> findAvailability(@PathVariable UUID id) {
		return mentorshipRequestService.findAvailability(id);
	}

	@PostMapping("/{id}/meeting")
	@ResponseStatus(HttpStatus.CREATED)
	public MeetingResponse scheduleMeeting(
			@PathVariable UUID id,
			@Valid @RequestBody ScheduleMentorshipRequest request) {
		return mentorshipRequestService.scheduleMeeting(id, request);
	}

	@GetMapping("/{id}/alternatives")
	public List<MentorProfileResponse> findAlternatives(@PathVariable UUID id) {
		return mentorshipRequestService.findAlternatives(id);
	}

	@GetMapping("/{id}/meetings")
	public List<MeetingResponse> findMeetings(@PathVariable UUID id) {
		return meetingService.findTimelineForRequest(id);
	}

	@PostMapping("/{id}/meetings")
	@ResponseStatus(HttpStatus.CREATED)
	public MeetingResponse logMeeting(
			@PathVariable UUID id,
			@Valid @RequestBody LogMeetingRequest request) {
		return meetingService.logMeeting(id, request);
	}
}
