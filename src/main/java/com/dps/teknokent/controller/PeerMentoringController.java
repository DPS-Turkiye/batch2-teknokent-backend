package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreatePeerMentoringRequest;
import com.dps.teknokent.dto.responses.PeerMentorCardResponse;
import com.dps.teknokent.dto.responses.PeerMentoringRequestResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.PeerMentoringService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Peer mentoring for ODTÜ students (the student workspace's Akran Mentörlüğü). */
@RestController
@RequestMapping("/api/peer-mentoring")
@RequiredArgsConstructor
@Tag(name = "Peer mentoring")
public class PeerMentoringController {

	private final PeerMentoringService peerMentoringService;

	@GetMapping("/mentors")
	public List<PeerMentorCardResponse> mentors(
			@RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) PeerTopicCategory category,
			@RequestParam(required = false) String q) {
		return peerMentoringService.mentors(CurrentActor.resolve(actorId), category, q);
	}

	/** {@code as=MENTOR} for the requests a peer mentor received; the student's own requests otherwise. */
	@GetMapping("/requests")
	public List<PeerMentoringRequestResponse> requests(
			@RequestParam(required = false) UUID actorId,
			@RequestParam(name = "as", required = false) UserRole side) {
		return peerMentoringService.requests(CurrentActor.resolve(actorId),
				side == UserRole.MENTOR ? UserRole.MENTOR : UserRole.MENTEE);
	}

	@PostMapping("/requests")
	@ResponseStatus(HttpStatus.CREATED)
	public PeerMentoringRequestResponse create(@Valid @RequestBody CreatePeerMentoringRequest request) {
		return peerMentoringService.create(CurrentActor.resolve(request.actorId()), request);
	}
}
