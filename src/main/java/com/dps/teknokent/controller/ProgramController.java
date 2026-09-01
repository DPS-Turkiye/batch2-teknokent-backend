package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.AddProgramMentorRequest;
import com.dps.teknokent.dto.requests.AddProgramStartupRequest;
import com.dps.teknokent.dto.requests.CreateProgramRequest;
import com.dps.teknokent.dto.requests.DecideProgramMembershipRequest;
import com.dps.teknokent.dto.responses.ProgramInvitationResponse;
import com.dps.teknokent.dto.responses.ProgramResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.ProgramService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/programs")
@RequiredArgsConstructor
@Tag(name = "Programs")
public class ProgramController {

	private final ProgramService programService;

	@GetMapping
	public List<ProgramResponse> list(@RequestParam(required = false) UUID actorId) {
		return programService.list(resolveActorId(actorId));
	}

	@GetMapping("/invitations")
	public List<ProgramInvitationResponse> invitations(@RequestParam(required = false) UUID actorId) {
		return programService.listInvitations(resolveActorId(actorId));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProgramResponse create(@Valid @RequestBody CreateProgramRequest request) {
		return programService.create(resolveActorId(request.actorId()), request.name());
	}

	@PostMapping("/{id}/startups")
	@ResponseStatus(HttpStatus.CREATED)
	public ProgramResponse addStartup(
			@PathVariable UUID id,
			@Valid @RequestBody AddProgramStartupRequest request) {
		return programService.addStartup(resolveActorId(request.actorId()), id, request.organizationId());
	}

	@DeleteMapping("/{id}/startups/{organizationId}")
	public ProgramResponse removeStartup(
			@PathVariable UUID id,
			@PathVariable UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return programService.removeStartup(resolveActorId(actorId), id, organizationId);
	}

	@PostMapping("/{id}/mentors")
	@ResponseStatus(HttpStatus.CREATED)
	public ProgramResponse inviteMentor(
			@PathVariable UUID id,
			@Valid @RequestBody AddProgramMentorRequest request) {
		return programService.inviteMentor(resolveActorId(request.actorId()), id, request.mentorId());
	}

	@PostMapping("/{id}/mentor-requests")
	@ResponseStatus(HttpStatus.CREATED)
	public ProgramResponse requestToJoin(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return programService.requestToJoin(resolveActorId(actorId), id);
	}

	@PostMapping("/{id}/mentor-requests/{mentorId}/decide")
	public ProgramResponse decideMentorRequest(
			@PathVariable UUID id,
			@PathVariable UUID mentorId,
			@Valid @RequestBody DecideProgramMembershipRequest request) {
		return programService.decideMentorRequest(
				resolveActorId(request.actorId()),
				id,
				mentorId,
				Boolean.TRUE.equals(request.accepted()));
	}

	@PostMapping("/{id}/invitations/decide")
	public ProgramInvitationResponse decideInvitation(
			@PathVariable UUID id,
			@Valid @RequestBody DecideProgramMembershipRequest request) {
		return programService.decideInvitation(
				resolveActorId(request.actorId()),
				id,
				Boolean.TRUE.equals(request.accepted()));
	}

	private static UUID resolveActorId(UUID requestedActorId) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
			return principal.id();
		}
		return requestedActorId;
	}
}
