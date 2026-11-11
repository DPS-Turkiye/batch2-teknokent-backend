package com.dps.teknokent.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CheckInRequest;
import com.dps.teknokent.dto.responses.AttendanceStatusResponse;
import com.dps.teknokent.dto.responses.ProgramAttendanceReportResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.ProgramAttendanceService;
import com.dps.teknokent.util.AttendanceExport;
import com.dps.teknokent.util.SessionsExport;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Program attendance")
public class ProgramAttendanceController {

	private final ProgramAttendanceService attendanceService;

	/** The caller's own check-in for the program week running today. */
	@GetMapping("/api/programs/mine/attendance")
	public AttendanceStatusResponse mine(@RequestParam(required = false) UUID actorId) {
		return attendanceService.status(CurrentActor.resolve(actorId));
	}

	@PostMapping("/api/programs/mine/attendance")
	@ResponseStatus(HttpStatus.CREATED)
	public AttendanceStatusResponse checkIn(@Valid @RequestBody CheckInRequest request) {
		return attendanceService.checkIn(CurrentActor.resolve(request.actorId()), request.week());
	}

	/** {@code from} and {@code to} are inclusive days; the program's start to today without them. */
	@GetMapping("/api/coordinator/programs/{id}/attendance")
	public ProgramAttendanceReportResponse report(
			@PathVariable UUID id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID actorId) {
		return attendanceService.report(CurrentActor.resolve(actorId), id, from, to);
	}

	@GetMapping("/api/coordinator/programs/{id}/attendance/export")
	public ResponseEntity<byte[]> export(
			@PathVariable UUID id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "XLSX") SessionsExport.Format format,
			@RequestParam(required = false) UUID actorId) {
		ProgramAttendanceReportResponse report = attendanceService.report(CurrentActor.resolve(actorId), id, from, to);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, SessionsExport.contentType(format))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(AttendanceExport.filename(report, format))
						.build()
						.toString())
				.body(AttendanceExport.write(report, format));
	}
}
