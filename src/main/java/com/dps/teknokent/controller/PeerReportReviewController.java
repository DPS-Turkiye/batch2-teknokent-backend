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

import com.dps.teknokent.domain.PeerReportKind;
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
			@RequestParam(required = false) PeerReportKind kind,
			@RequestParam(required = false) PeerReportStatus status) {
		return reviewService.queue(CurrentActor.resolve(actorId), programId, kind, status);
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

	@PostMapping("/meetings/{reportId}/approve")
	public PeerReviewedReportResponse approveMeetingReport(@PathVariable UUID reportId,
			@RequestBody(required = false) PeerReportReviewActorRequest request) {
		return reviewService.approve(actor(request), PeerReportKind.MEETING, reportId);
	}

	@PostMapping("/days/{reportId}/approve")
	public PeerReviewedReportResponse approveDailyReport(@PathVariable UUID reportId,
			@RequestBody(required = false) PeerReportReviewActorRequest request) {
		return reviewService.approve(actor(request), PeerReportKind.DAILY, reportId);
	}

	@PostMapping("/weeks/{reportId}/approve")
	public PeerReviewedReportResponse approveWeeklyReport(@PathVariable UUID reportId,
			@RequestBody(required = false) PeerReportReviewActorRequest request) {
		return reviewService.approve(actor(request), PeerReportKind.WEEKLY, reportId);
	}

	@PostMapping("/meetings/{reportId}/revision")
	public PeerReviewedReportResponse reviseMeetingReport(@PathVariable UUID reportId,
			@Valid @RequestBody RequestPeerReportRevisionRequest request) {
		return reviewService.requestRevision(CurrentActor.resolve(request.actorId()), PeerReportKind.MEETING, reportId,
				request.note());
	}

	@PostMapping("/days/{reportId}/revision")
	public PeerReviewedReportResponse reviseDailyReport(@PathVariable UUID reportId,
			@Valid @RequestBody RequestPeerReportRevisionRequest request) {
		return reviewService.requestRevision(CurrentActor.resolve(request.actorId()), PeerReportKind.DAILY, reportId,
				request.note());
	}

	@PostMapping("/weeks/{reportId}/revision")
	public PeerReviewedReportResponse reviseWeeklyReport(@PathVariable UUID reportId,
			@Valid @RequestBody RequestPeerReportRevisionRequest request) {
		return reviewService.requestRevision(CurrentActor.resolve(request.actorId()), PeerReportKind.WEEKLY, reportId,
				request.note());
	}

	private static UUID actor(PeerReportReviewActorRequest request) {
		return CurrentActor.resolve(request == null ? null : request.actorId());
	}
}
