package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.DeclineMeetingProposalRequest;
import com.dps.teknokent.dto.requests.ProposeMeetingRequest;
import com.dps.teknokent.dto.requests.RescheduleMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingProposalResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.MeetingProposalService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Meeting proposals")
public class MeetingProposalController {

	private final MeetingProposalService proposalService;

	@GetMapping("/api/coordinator/meeting-proposals")
	public List<MeetingProposalResponse> coordinatorList(
			@RequestParam(required = false) UUID programId,
			@RequestParam(required = false) UUID mentorshipId,
			@RequestParam(required = false) UUID actorId) {
		return proposalService.coordinatorList(CurrentActor.resolve(actorId), programId, mentorshipId);
	}

	@PostMapping("/api/coordinator/mentorships/{id}/meeting-proposals")
	@ResponseStatus(HttpStatus.CREATED)
	public MeetingProposalResponse propose(@PathVariable UUID id, @Valid @RequestBody ProposeMeetingRequest request) {
		return proposalService.propose(CurrentActor.resolve(request.actorId()), id, request);
	}

	@PostMapping("/api/coordinator/meeting-proposals/{id}/remind")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remind(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		proposalService.remind(CurrentActor.resolve(actorId), id);
	}

	@DeleteMapping("/api/coordinator/meeting-proposals/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void withdraw(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		proposalService.withdraw(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/api/meetings/{id}/reschedule-proposals")
	@ResponseStatus(HttpStatus.CREATED)
	public MeetingProposalResponse proposeReschedule(
			@PathVariable UUID id,
			@Valid @RequestBody RescheduleMeetingRequest request) {
		return proposalService.proposeReschedule(CurrentActor.resolve(request.actorId()), id, request);
	}

	@GetMapping("/api/meeting-proposals")
	public List<MeetingProposalResponse> mine(@RequestParam(required = false) UUID actorId) {
		return proposalService.mine(CurrentActor.resolve(actorId));
	}

	@PostMapping("/api/meeting-proposals/{id}/accept")
	public MeetingProposalResponse accept(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return proposalService.accept(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/api/meeting-proposals/{id}/decline")
	public MeetingProposalResponse decline(
			@PathVariable UUID id,
			@Valid @RequestBody DeclineMeetingProposalRequest request) {
		return proposalService.decline(CurrentActor.resolve(request.actorId()), id, request);
	}
}
