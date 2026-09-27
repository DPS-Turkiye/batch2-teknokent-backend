package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.PostProgramAnnouncementRequest;
import com.dps.teknokent.dto.responses.ProgramAnnouncementResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.ProgramAnnouncementService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Program announcements")
public class ProgramAnnouncementController {

	private final ProgramAnnouncementService announcementService;

	@GetMapping("/api/coordinator/programs/{id}/announcements")
	public List<ProgramAnnouncementResponse> coordinatorList(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return announcementService.coordinatorList(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/api/coordinator/programs/{id}/announcements")
	@ResponseStatus(HttpStatus.CREATED)
	public ProgramAnnouncementResponse post(
			@PathVariable UUID id,
			@Valid @RequestBody PostProgramAnnouncementRequest request) {
		return announcementService.post(CurrentActor.resolve(request.actorId()), id, request);
	}

	@GetMapping("/api/program-announcements")
	public List<ProgramAnnouncementResponse> mine(@RequestParam(required = false) UUID actorId) {
		return announcementService.mine(CurrentActor.resolve(actorId));
	}
}
