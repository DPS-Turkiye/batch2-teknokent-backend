package com.dps.teknokent.controller;

import com.dps.teknokent.security.CurrentActor;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.DecideAccountStatusRequest;
import com.dps.teknokent.dto.requests.RevokeSignInsRequest;
import com.dps.teknokent.dto.requests.UpdateUserProfileRequest;
import com.dps.teknokent.dto.responses.AccountDecisionResponse;
import com.dps.teknokent.dto.responses.TeamHistoryResponse;
import com.dps.teknokent.dto.responses.UserProfileResponse;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.service.AppUserService;
import com.dps.teknokent.service.TeamMembershipService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users")
public class AppUserController {

	private final AppUserService appUserService;
	private final TeamMembershipService teamMembershipService;

	/** Admins only. */
	@GetMapping
	public List<UserResponse> findAll(
			@RequestParam(required = false) UserRole role,
			@RequestParam(required = false) UserAccountStatus status,
			@RequestParam(required = false) UUID actorId) {
		return appUserService.findAll(CurrentActor.resolve(actorId), role, status);
	}

	/** The person themself, an admin, or someone related to them; "not found" for anyone else. */
	@GetMapping("/{id}")
	public UserResponse findById(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return appUserService.findById(CurrentActor.resolve(actorId), id);
	}

	/** Every startup the person was in; for admins and the person's coordinators only. */
	@GetMapping("/{id}/team-history")
	public TeamHistoryResponse teamHistory(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return teamMembershipService.history(CurrentActor.resolve(actorId), id);
	}

	/** Same audience as {@link #findById}. */
	@GetMapping("/{id}/profile")
	public UserProfileResponse profile(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return appUserService.findProfile(CurrentActor.resolve(actorId), id);
	}

	@PutMapping("/{id}/profile")
	public UserProfileResponse updateProfile(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateUserProfileRequest request) {
		return appUserService.updateProfile(id, new UpdateUserProfileRequest(CurrentActor.resolve(request.actorId()),
				request.fullName(), request.jobTitle(), request.phone(), request.bio(), request.linkedinUrl(),
				request.expertise(), request.contactPreferences()));
	}

	@PatchMapping("/{id}/status")
	public AccountDecisionResponse decideStatus(
			@PathVariable UUID id,
			@Valid @RequestBody DecideAccountStatusRequest request) {
		return appUserService.decideStatus(id,
				new DecideAccountStatusRequest(CurrentActor.resolve(request.actorId()), request.status()));
	}

	@PostMapping("/{id}/sign-ins/revoke")
	public UserResponse revokeSignIns(
			@PathVariable UUID id,
			@Valid @RequestBody RevokeSignInsRequest request) {
		return appUserService.revokeSignIns(id, CurrentActor.resolve(request.actorId()));
	}
}
