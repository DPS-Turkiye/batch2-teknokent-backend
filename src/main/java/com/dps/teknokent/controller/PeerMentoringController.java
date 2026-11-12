package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreatePeerMentoringRequest;
import com.dps.teknokent.dto.requests.SavePeerMentorProfileRequest;
import com.dps.teknokent.dto.responses.AppointmentResponse;
import com.dps.teknokent.dto.responses.PeerCategoryCountResponse;
import com.dps.teknokent.dto.responses.PeerMentorCardResponse;
import com.dps.teknokent.dto.responses.PeerMentorDetailResponse;
import com.dps.teknokent.dto.responses.PeerMentorProfileResponse;
import com.dps.teknokent.dto.responses.PeerMentoringRequestResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.PeerAppointmentService;
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
	private final PeerAppointmentService peerAppointmentService;

	/** {@code subTopic} keeps the mentors offering it; {@code availableThisWeek} those with a free slot this week. */
	@GetMapping("/mentors")
	public List<PeerMentorCardResponse> mentors(
			@RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) PeerTopicCategory category,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String subTopic,
			@RequestParam(defaultValue = "false") boolean availableThisWeek) {
		return peerMentoringService.mentors(CurrentActor.resolve(actorId), category, q, subTopic, availableThisWeek);
	}

	/** One peer mentor of the student's program, for their profile page; 404 for anyone the directory doesn't list. */
	@GetMapping("/mentors/{mentorId}")
	public PeerMentorDetailResponse mentor(@PathVariable UUID mentorId, @RequestParam(required = false) UUID actorId) {
		return peerMentoringService.mentor(CurrentActor.resolve(actorId), mentorId);
	}

	/** Mentors per category active in the student's program (Keşfet's category cards). */
	@GetMapping("/mentor-counts")
	public List<PeerCategoryCountResponse> mentorCounts(@RequestParam(required = false) UUID actorId) {
		return peerMentoringService.mentorCounts(CurrentActor.resolve(actorId));
	}

	/** {@code as=MENTOR}: the categories of the programs the caller mentors in; the student's program's otherwise. */
	@GetMapping("/categories")
	public List<PeerTopicCategory> categories(
			@RequestParam(required = false) UUID actorId,
			@RequestParam(name = "as", required = false) UserRole side) {
		return peerMentoringService.categories(CurrentActor.resolve(actorId),
				side == UserRole.MENTOR ? UserRole.MENTOR : UserRole.MENTEE);
	}

	/** {@code as=MENTOR} for the requests a peer mentor received; the student's own requests otherwise. */
	@GetMapping("/requests")
	public List<PeerMentoringRequestResponse> requests(
			@RequestParam(required = false) UUID actorId,
			@RequestParam(name = "as", required = false) UserRole side) {
		return peerMentoringService.requests(CurrentActor.resolve(actorId),
				side == UserRole.MENTOR ? UserRole.MENTOR : UserRole.MENTEE);
	}

	/** The meetings booked on the student's peer requests, as mentee and as mentor, soonest first. */
	@GetMapping("/appointments")
	public List<AppointmentResponse> appointments(@RequestParam(required = false) UUID actorId) {
		return peerAppointmentService.appointments(CurrentActor.resolve(actorId));
	}

	/** The signed-in peer mentor's own profile; 404 until the wizard publishes one. */
	@GetMapping("/profile")
	public PeerMentorProfileResponse profile(@RequestParam(required = false) UUID actorId) {
		return peerMentoringService.profile(CurrentActor.resolve(actorId));
	}

	@PutMapping("/profile")
	public PeerMentorProfileResponse saveProfile(@Valid @RequestBody SavePeerMentorProfileRequest request) {
		return peerMentoringService.saveProfile(CurrentActor.resolve(request.actorId()), request);
	}

	@PostMapping("/requests")
	@ResponseStatus(HttpStatus.CREATED)
	public PeerMentoringRequestResponse create(@Valid @RequestBody CreatePeerMentoringRequest request) {
		return peerMentoringService.create(CurrentActor.resolve(request.actorId()), request);
	}
}
