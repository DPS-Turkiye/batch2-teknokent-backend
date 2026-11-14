package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.domain.PeerReportStatus;
import com.dps.teknokent.dto.requests.PeerReportReviewActorRequest;
import com.dps.teknokent.dto.requests.RequestPeerReportRevisionRequest;
import com.dps.teknokent.dto.responses.PeerReviewQueueItemResponse;
import com.dps.teknokent.dto.responses.PeerReviewedReportResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.PeerReportReviewService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Review of peer-mentor reports by the coordinators of the mentor's program (and admins): the list, a report in
 * full, and approve or "Revizyon iste". Drafts never show here; a report outside the reviewer's programs is not found.
 */
@RestController
@RequestMapping("/api/peer-mentoring/reports/review")
@RequiredArgsConstructor
@Tag(name = "Peer mentoring report review")
public class PeerReportReviewController {

	private final PeerReportReviewService reviewService;

	@GetMapping
	public List<PeerReviewQueueItemResponse> queue(
			@RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) UUID programId,
			@RequestParam(required = false) PeerReportStatus status) {
		return reviewService.queue(CurrentActor.resolve(actorId), programId, status);
	}

	@GetMapping("/meetings/{reportId}")
	public PeerReviewedReportResponse meetingReport(@PathVariable UUID reportId,
			@RequestParam(required = false) UUID actorId) {
		return reviewService.meetingReport(CurrentActor.resolve(actorId), reportId);
	}

	@GetMapping("/days/{reportId}")
	public PeerReviewedReportResponse dailyReport(@PathVariable UUID reportId,
			@RequestParam(required = false) UUID actorId) {
		return reviewService.dailyReport(CurrentActor.resolve(actorId), reportId);
	}

	@GetMapping("/weeks/{reportId}")
	public PeerReviewedReportResponse weeklyReport(@PathVariable UUID reportId,
			@RequestParam(required = false) UUID actorId) {
		return reviewService.weeklyReport(CurrentActor.resolve(actorId), reportId);
	}

	/** Approves the week, and with it the week's sent daily and meeting reports. */
	@PostMapping("/weeks/{reportId}/approve")
	public PeerReviewedReportResponse approveWeeklyReport(@PathVariable UUID reportId,
			@RequestBody(required = false) PeerReportReviewActorRequest request) {
		return reviewService.approveWeek(actor(request), reportId);
	}

	@PostMapping("/weeks/{reportId}/revision")
	public PeerReviewedReportResponse reviseWeeklyReport(@PathVariable UUID reportId,
			@Valid @RequestBody RequestPeerReportRevisionRequest request) {
		return reviewService.requestWeekRevision(CurrentActor.resolve(request.actorId()), reportId, request.note());
	}

	private static UUID actor(PeerReportReviewActorRequest request) {
		return CurrentActor.resolve(request == null ? null : request.actorId());
	}
}
