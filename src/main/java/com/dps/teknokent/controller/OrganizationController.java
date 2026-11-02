package com.dps.teknokent.controller;

import com.dps.teknokent.dto.responses.OrganizationPendingMemberResponse;
import com.dps.teknokent.security.CurrentActor;
import java.util.List;
import java.util.UUID;

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

import com.dps.teknokent.dto.requests.ActorRequest;
import com.dps.teknokent.dto.requests.CreateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.dto.requests.CreateStartupRequest;
import com.dps.teknokent.dto.requests.JoinStartupRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationStatusRequest;
import com.dps.teknokent.dto.responses.OrganizationInviteResponse;
import com.dps.teknokent.dto.responses.OrganizationMemberResponse;
import com.dps.teknokent.dto.responses.OrganizationResponse;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.service.AppUserService;
import com.dps.teknokent.service.OrganizationService;
import com.dps.teknokent.service.RegistrationService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations")
public class OrganizationController {

	private final OrganizationService organizationService;
	private final AppUserService appUserService;
	private final RegistrationService registrationService;

	@GetMapping
	public List<OrganizationResponse> findAll() {
		return organizationService.findAll();
	}

	@GetMapping("/{id}")
	public OrganizationResponse findById(@PathVariable UUID id) {
		return organizationService.findById(id);
	}

	@PutMapping("/{id}")
	public OrganizationResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateOrganizationRequest request) {
		return organizationService.update(id, new UpdateOrganizationRequest(CurrentActor.resolve(request.actorId()),
				request.name(), request.description(), request.website(), request.problem(), request.solution(),
				request.currentSolution(), request.targetUser(), request.sector(), request.maturity(),
				request.programLevel(), request.supportAreas(), request.fundingStage(), request.foundedYear(),
				request.mvpUrl()));
	}

	@PutMapping("/{id}/status")
	public OrganizationResponse updateStatus(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateOrganizationStatusRequest request) {
		return organizationService.updateStatus(id, new UpdateOrganizationStatusRequest(
				CurrentActor.resolve(request.actorId()), request.statusVersion(), request.statusSummary(),
				request.completionPercent()));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request) {
		return organizationService.create(request);
	}

	/** "Kodum var" on /startup: an approved mentee without a startup joins one of their program's startups. */
	@PostMapping("/join")
	public UserResponse join(@Valid @RequestBody JoinStartupRequest request) {
		return registrationService.joinStartup(CurrentActor.resolve(request.actorId()), request.inviteCode());
	}

	/** "Startup'ımı oluştur" on /startup; the startup waits for its program's coordinator. */
	@PostMapping("/mine")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse createOwn(@Valid @RequestBody CreateStartupRequest request) {
		return registrationService.createStartup(CurrentActor.resolve(request.actorId()), request);
	}

	@PostMapping("/{id}/resubmit")
	public OrganizationResponse resubmit(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return organizationService.resubmit(id, CurrentActor.resolve(actorId));
	}

	@GetMapping("/{id}/members")
	public List<OrganizationMemberResponse> members(@PathVariable UUID id) {
		return appUserService.findByOrganization(id);
	}

	@PostMapping("/{id}/members")
	@ResponseStatus(HttpStatus.CREATED)
	public OrganizationMemberResponse addMember(
			@PathVariable UUID id,
			@Valid @RequestBody CreateOrganizationMemberRequest request) {
		return appUserService.addOrganizationMember(id, request);
	}

	@PatchMapping("/{id}/members/{memberId}")
	public OrganizationMemberResponse updateMember(
			@PathVariable UUID id,
			@PathVariable UUID memberId,
			@Valid @RequestBody UpdateOrganizationMemberRequest request) {
		return appUserService.updateOrganizationMember(id, memberId, new UpdateOrganizationMemberRequest(
				CurrentActor.resolve(request.actorId()), request.fullName(), request.jobTitle()));
	}

	@DeleteMapping("/{id}/members/{memberId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeMember(
			@PathVariable UUID id,
			@PathVariable UUID memberId,
			@RequestParam(required = false) UUID actorId) {
		appUserService.removeOrganizationMember(id, memberId, CurrentActor.resolve(actorId));
	}

	@GetMapping("/{id}/pending-members")
	public List<OrganizationPendingMemberResponse> pendingMembers(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return appUserService.findPendingMembers(id, CurrentActor.resolve(actorId));
	}

	@DeleteMapping("/{id}/pending-members/{pendingId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removePendingMember(
			@PathVariable UUID id,
			@PathVariable UUID pendingId,
			@RequestParam(required = false) UUID actorId) {
		appUserService.removePendingMember(id, pendingId, CurrentActor.resolve(actorId));
	}

	@GetMapping("/{id}/invite-code")
	public OrganizationInviteResponse inviteCode(@PathVariable UUID id, @RequestParam UUID actorId) {
		return organizationService.getInviteCode(id, actorId);
	}

	@PostMapping("/{id}/invite-code")
	public OrganizationInviteResponse regenerateInviteCode(
			@PathVariable UUID id,
			@Valid @RequestBody ActorRequest request) {
		return organizationService.regenerateInviteCode(id, request.actorId());
	}
}
