package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.MoveMemberToTeamRequest;
import com.dps.teknokent.dto.responses.MemberTeamMoveOptionsResponse;
import com.dps.teknokent.dto.responses.MemberTeamMoveResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.MemberTeamMoveService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * An admin, or a coordinator of both teams' programs, moves a team member to another team. Changing who is in a
 * team is taken on the caller's own verified sign-in only, never on the shared service credentials.
 */
@RestController
@RequestMapping("/api/users/{userId}/team-move")
@RequiredArgsConstructor
@Tag(name = "Team moves")
public class MemberTeamMoveController {

	private final MemberTeamMoveService memberTeamMoveService;

	@GetMapping
	@Operation(summary = "The person's team, why they cannot move if so, and the teams this caller may move them to")
	public MemberTeamMoveOptionsResponse options(@PathVariable UUID userId) {
		return memberTeamMoveService.options(CurrentActor.requireSignedIn(), userId);
	}

	@PostMapping
	@Operation(summary = "Move the person to another team at once; their team's records stay with the old team")
	public MemberTeamMoveResponse move(@PathVariable UUID userId, @Valid @RequestBody MoveMemberToTeamRequest request) {
		return memberTeamMoveService.move(CurrentActor.requireSignedIn(), userId, request.targetOrganizationId());
	}
}
