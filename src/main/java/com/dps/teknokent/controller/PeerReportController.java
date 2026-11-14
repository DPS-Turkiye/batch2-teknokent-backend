package com.dps.teknokent.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
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

import com.dps.teknokent.dto.requests.SavePeerActivityRequest;
import com.dps.teknokent.dto.requests.SavePeerDailyReportRequest;
import com.dps.teknokent.dto.requests.SavePeerMeetingReportRequest;
import com.dps.teknokent.dto.requests.SavePeerWeeklyReportRequest;
import com.dps.teknokent.dto.requests.UpdatePeerReportActionRequest;
import com.dps.teknokent.dto.responses.PeerActivityResponse;
import com.dps.teknokent.dto.responses.PeerDailyReportResponse;
import com.dps.teknokent.dto.responses.PeerMeetingReportResponse;
import com.dps.teknokent.dto.responses.PeerReportMeetingResponse;
import com.dps.teknokent.dto.responses.PeerReportWeekResponse;
import com.dps.teknokent.dto.responses.PeerWeeklyReportResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.PeerDailyReportService;
import com.dps.teknokent.service.PeerMeetingReportService;
import com.dps.teknokent.service.PeerWeeklyReportService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * A peer mentor's own reports: meeting reports, the daily report with its activities, and the weekly summary. Only
 * the mentor reaches these; another mentor's meeting, day or activity is not found. The mentee has no route here.
 */
@RestController
@RequestMapping("/api/peer-mentoring/reports")
@RequiredArgsConstructor
@Tag(name = "Peer mentoring reports")
public class PeerReportController {

	private final PeerMeetingReportService meetingReportService;
	private final PeerDailyReportService dailyReportService;
	private final PeerWeeklyReportService weeklyReportService;

	/** Held peer meetings between two days (both included) with their report state. */
	@GetMapping("/meetings")
	public List<PeerReportMeetingResponse> meetings(
			@RequestParam(required = false) UUID actorId,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		return meetingReportService.meetings(CurrentActor.resolve(actorId), from, to);
	}

	/** The meeting's report, or its calendar prefill when none was saved yet. */
	@GetMapping("/meetings/{meetingId}")
	public PeerMeetingReportResponse meetingReport(@PathVariable UUID meetingId,
			@RequestParam(required = false) UUID actorId) {
		return meetingReportService.get(CurrentActor.resolve(actorId), meetingId);
	}

	@PutMapping("/meetings/{meetingId}")
	public PeerMeetingReportResponse saveMeetingReport(@PathVariable UUID meetingId,
			@Valid @RequestBody SavePeerMeetingReportRequest request) {
		return meetingReportService.save(CurrentActor.resolve(request.actorId()), meetingId, request);
	}

	@PostMapping("/meetings/{meetingId}/submit")
	public PeerMeetingReportResponse submitMeetingReport(@PathVariable UUID meetingId,
			@RequestParam(required = false) UUID actorId) {
		return meetingReportService.submit(CurrentActor.resolve(actorId), meetingId);
	}

	@PatchMapping("/meetings/{meetingId}/actions/{actionId}")
	public PeerMeetingReportResponse updateAction(@PathVariable UUID meetingId, @PathVariable UUID actionId,
			@RequestBody UpdatePeerReportActionRequest request) {
		return meetingReportService.updateAction(CurrentActor.resolve(request.actorId()), meetingId, actionId,
				request.done());
	}

	/** The five day cards of the week starting on {@code week} (a Monday). */
	@GetMapping("/days")
	public PeerReportWeekResponse days(@RequestParam(required = false) UUID actorId,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate week) {
		return dailyReportService.week(CurrentActor.resolve(actorId), week);
	}

	@GetMapping("/days/{date}")
	public PeerDailyReportResponse day(@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date,
			@RequestParam(required = false) UUID actorId) {
		return dailyReportService.day(CurrentActor.resolve(actorId), date);
	}

	@PutMapping("/days/{date}")
	public PeerDailyReportResponse saveDay(@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date,
			@Valid @RequestBody SavePeerDailyReportRequest request) {
		return dailyReportService.save(CurrentActor.resolve(request.actorId()), date, request);
	}

	@PostMapping("/days/{date}/submit")
	public PeerDailyReportResponse submitDay(@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date,
			@RequestParam(required = false) UUID actorId) {
		return dailyReportService.submit(CurrentActor.resolve(actorId), date);
	}

	@PostMapping("/activities")
	@ResponseStatus(HttpStatus.CREATED)
	public PeerActivityResponse addActivity(@Valid @RequestBody SavePeerActivityRequest request) {
		return dailyReportService.addActivity(CurrentActor.resolve(request.actorId()), request);
	}

	@PutMapping("/activities/{activityId}")
	public PeerActivityResponse updateActivity(@PathVariable UUID activityId,
			@Valid @RequestBody SavePeerActivityRequest request) {
		return dailyReportService.updateActivity(CurrentActor.resolve(request.actorId()), activityId, request);
	}

	@DeleteMapping("/activities/{activityId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteActivity(@PathVariable UUID activityId, @RequestParam(required = false) UUID actorId) {
		dailyReportService.deleteActivity(CurrentActor.resolve(actorId), activityId);
	}

	@GetMapping("/weeks/{weekStart}")
	public PeerWeeklyReportResponse week(@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate weekStart,
			@RequestParam(required = false) UUID actorId) {
		return weeklyReportService.get(CurrentActor.resolve(actorId), weekStart);
	}

	@PutMapping("/weeks/{weekStart}")
	public PeerWeeklyReportResponse saveWeek(@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate weekStart,
			@Valid @RequestBody SavePeerWeeklyReportRequest request) {
		return weeklyReportService.save(CurrentActor.resolve(request.actorId()), weekStart, request);
	}

	@PostMapping("/weeks/{weekStart}/submit")
	public PeerWeeklyReportResponse submitWeek(@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate weekStart,
			@RequestParam(required = false) UUID actorId) {
		return weeklyReportService.submit(CurrentActor.resolve(actorId), weekStart);
	}
}
