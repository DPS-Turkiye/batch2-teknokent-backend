package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.dto.requests.SessionCheckInRequest;
import com.dps.teknokent.dto.responses.ProgramAttendanceSessionsResponse;
import com.dps.teknokent.dto.responses.SessionCheckInResponse;
import com.dps.teknokent.dto.responses.SessionQrResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.ProgramSessionAttendanceService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Program attendance")
public class ProgramSessionAttendanceController {

	private static final String SESSION = "/api/coordinator/programs/{id}/attendance/sessions/{week}/{kind}";

	private final ProgramSessionAttendanceService sessionAttendanceService;

	/** A participant scanned a session's QR code. The token travels in the body, out of request logs. */
	@PostMapping("/api/attendance/check-in")
	public SessionCheckInResponse checkIn(@Valid @RequestBody SessionCheckInRequest request) {
		return sessionAttendanceService.checkIn(CurrentActor.resolve(request.actorId()), request.token());
	}

	@GetMapping("/api/coordinator/programs/{id}/attendance/sessions")
	public ProgramAttendanceSessionsResponse sessions(@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return sessionAttendanceService.sessions(CurrentActor.resolve(actorId), id);
	}

	@PostMapping(SESSION + "/qr")
	public SessionQrResponse qr(@PathVariable UUID id, @PathVariable int week,
			@PathVariable AttendanceSessionKind kind, @RequestParam(required = false) UUID actorId) {
		return sessionAttendanceService.qr(CurrentActor.resolve(actorId), id, week, kind);
	}

	@PostMapping(SESSION + "/qr/regenerate")
	public SessionQrResponse regenerate(@PathVariable UUID id, @PathVariable int week,
			@PathVariable AttendanceSessionKind kind, @RequestParam(required = false) UUID actorId) {
		return sessionAttendanceService.regenerate(CurrentActor.resolve(actorId), id, week, kind);
	}

	@PutMapping(SESSION + "/participants/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markPresent(@PathVariable UUID id, @PathVariable int week, @PathVariable AttendanceSessionKind kind,
			@PathVariable UUID userId, @RequestParam(required = false) UUID actorId) {
		sessionAttendanceService.markPresent(CurrentActor.resolve(actorId), id, week, kind, userId);
	}

	@DeleteMapping(SESSION + "/participants/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unmark(@PathVariable UUID id, @PathVariable int week, @PathVariable AttendanceSessionKind kind,
			@PathVariable UUID userId, @RequestParam(required = false) UUID actorId) {
		sessionAttendanceService.unmark(CurrentActor.resolve(actorId), id, week, kind, userId);
	}
}
