package com.dps.teknokent.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.AdminOverviewResponse;
import com.dps.teknokent.dto.responses.ProgramMeetingRatingsResponse;
import com.dps.teknokent.dto.responses.ProgramSessionsResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.ProgramReportService;
import com.dps.teknokent.util.SessionsExport;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator/programs/{id}")
@RequiredArgsConstructor
@Tag(name = "Program report")
public class ProgramReportController {

	private final ProgramReportService programReportService;

	@GetMapping("/report")
	public AdminOverviewResponse report(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return programReportService.report(CurrentActor.resolve(actorId), id);
	}

	/** {@code from} is an inclusive day in the app's time zone; without it every held meeting counts. */
	@GetMapping("/meeting-ratings")
	public ProgramMeetingRatingsResponse meetingRatings(
			@PathVariable UUID id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) UUID actorId) {
		return programReportService.meetingRatings(CurrentActor.resolve(actorId), id, from);
	}

	/** {@code from} and {@code to} are inclusive days in the app's time zone; the current month without them. */
	@GetMapping("/sessions")
	public ProgramSessionsResponse sessions(
			@PathVariable UUID id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID actorId) {
		return programReportService.sessions(CurrentActor.resolve(actorId), id, from, to);
	}

	@GetMapping("/sessions/export")
	public ResponseEntity<byte[]> exportSessions(
			@PathVariable UUID id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "XLSX") SessionsExport.Format format,
			@RequestParam(required = false) UUID actorId) {
		ProgramSessionsResponse report = programReportService.sessions(CurrentActor.resolve(actorId), id, from, to);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, SessionsExport.contentType(format))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(SessionsExport.filename(report, format))
						.build()
						.toString())
				.body(SessionsExport.write(report, format));
	}
}
