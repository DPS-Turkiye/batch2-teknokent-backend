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

import com.dps.teknokent.dto.requests.AnswerOfficeHoursAlternativeRequest;
import com.dps.teknokent.dto.requests.AnswerOfficeHoursRequest;
import com.dps.teknokent.dto.requests.CreateMentonOfficeHoursRequest;
import com.dps.teknokent.dto.requests.UpdateOfficeHoursAvailabilityRequest;
import com.dps.teknokent.dto.requests.UpdateOfficeHoursProfileRequest;
import com.dps.teknokent.dto.responses.OfficeHoursAcademicianMeetingResponse;
import com.dps.teknokent.dto.responses.OfficeHoursAcademicianResponse;
import com.dps.teknokent.dto.responses.OfficeHoursAvailabilityResponse;
import com.dps.teknokent.dto.responses.OfficeHoursInboxRequestResponse;
import com.dps.teknokent.dto.responses.OfficeHoursProfileResponse;
import com.dps.teknokent.dto.responses.OfficeHoursStudentRequestResponse;
import com.dps.teknokent.dto.responses.OfficeHoursTimeResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.OfficeHoursMeService;
import com.dps.teknokent.service.OfficeHoursStudentMeService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Office hours for the Menton account the frontend's server names: ODTÜ-signed-in academicians and students, no
 * Moodle launch needed. Like the rest of {@code /api/office-hours}, only that server may call it; a 404 for anyone
 * ODTÜ's record doesn't back.
 */
@RestController
@RequestMapping("/api/office-hours/me")
@RequiredArgsConstructor
@Tag(name = "Office hours")
public class OfficeHoursMeController {

	private final OfficeHoursMeService meService;
	private final OfficeHoursStudentMeService studentService;

	@GetMapping("/availability")
	public OfficeHoursAvailabilityResponse availability(@RequestParam(required = false) UUID actorId) {
		return meService.availability(CurrentActor.resolve(actorId));
	}

	@PutMapping("/availability")
	public OfficeHoursAvailabilityResponse saveAvailability(@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody UpdateOfficeHoursAvailabilityRequest request) {
		return meService.saveAvailability(CurrentActor.resolve(actorId), request);
	}

	/** Where and how students meet them, and whether students see them in the list at all. */
	@GetMapping("/profile")
	public OfficeHoursProfileResponse profile(@RequestParam(required = false) UUID actorId) {
		return meService.profile(CurrentActor.resolve(actorId));
	}

	@PutMapping("/profile")
	public OfficeHoursProfileResponse saveProfile(@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody UpdateOfficeHoursProfileRequest request) {
		return meService.saveProfile(CurrentActor.resolve(actorId), request);
	}

	/** Requests sent to the academician, newest first. */
	@GetMapping("/inbox")
	public List<OfficeHoursInboxRequestResponse> inbox(@RequestParam(required = false) UUID actorId) {
		return meService.inbox(CurrentActor.resolve(actorId));
	}

	@PostMapping("/requests/{requestId}/decision")
	public OfficeHoursInboxRequestResponse answer(@RequestParam(required = false) UUID actorId,
			@PathVariable UUID requestId, @Valid @RequestBody AnswerOfficeHoursRequest answer) {
		return meService.answer(CurrentActor.resolve(actorId), requestId, answer);
	}

	/** The academician's booked meetings, soonest first. */
	@GetMapping("/meetings")
	public List<OfficeHoursAcademicianMeetingResponse> meetings(@RequestParam(required = false) UUID actorId) {
		return meService.meetings(CurrentActor.resolve(actorId));
	}

	/** For a student: the academicians who chose to be listed. */
	@GetMapping("/academicians")
	public List<OfficeHoursAcademicianResponse> academicians(@RequestParam(required = false) UUID actorId) {
		return studentService.academicians(CurrentActor.resolve(actorId));
	}

	@GetMapping("/academicians/{academicianId}")
	public OfficeHoursAcademicianResponse academician(@RequestParam(required = false) UUID actorId,
			@PathVariable UUID academicianId) {
		return studentService.academician(CurrentActor.resolve(actorId), academicianId);
	}

	@GetMapping("/academicians/{academicianId}/slots")
	public List<OfficeHoursTimeResponse> slots(@RequestParam(required = false) UUID actorId,
			@PathVariable UUID academicianId) {
		return studentService.slots(CurrentActor.resolve(actorId), academicianId);
	}

	@PostMapping("/requests")
	@ResponseStatus(HttpStatus.CREATED)
	public OfficeHoursStudentRequestResponse request(@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody CreateMentonOfficeHoursRequest request) {
		return studentService.request(CurrentActor.resolve(actorId), request);
	}

	/** The student's own requests, newest first. */
	@GetMapping("/appointments")
	public List<OfficeHoursStudentRequestResponse> appointments(@RequestParam(required = false) UUID actorId) {
		return studentService.appointments(CurrentActor.resolve(actorId));
	}

	@PostMapping("/requests/{requestId}/alternative")
	public OfficeHoursStudentRequestResponse answerAlternative(@RequestParam(required = false) UUID actorId,
			@PathVariable UUID requestId, @Valid @RequestBody AnswerOfficeHoursAlternativeRequest answer) {
		return studentService.answerAlternative(CurrentActor.resolve(actorId), requestId, answer);
	}
}
