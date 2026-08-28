package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.dto.responses.OrganizationInviteResponse;
import com.dps.teknokent.dto.responses.OrganizationResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.InviteCodeGenerator;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrganizationService {

	private final OrganizationRepository organizationRepository;
	private final DomainLookupService domainLookupService;
	private final InviteCodeGenerator inviteCodeGenerator;

	@Transactional(readOnly = true)
	public List<OrganizationResponse> findAll() {
		return organizationRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
				.map(OrganizationResponse::fromEntity)
				.toList();
	}

	@Transactional(readOnly = true)
	public OrganizationResponse findById(UUID id) {
		return OrganizationResponse.fromEntity(domainLookupService.requireOrganization(id));
	}

	@Transactional
	public OrganizationResponse create(CreateOrganizationRequest request) {
		return OrganizationResponse.fromEntity(organizationRepository.saveAndFlush(Organization.fromRequest(request)));
	}

	@Transactional(readOnly = true)
	public OrganizationInviteResponse getInviteCode(UUID organizationId, UUID actorId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!isAdmin(actor) && !isFounder(organization, actorId)) {
			throw new InvalidRequestException("Only an admin or the organization founder can view the invite code");
		}
		if (organization.getInviteCode() == null) {
			throw new InvalidRequestException("Invite code has not been generated yet.");
		}
		return new OrganizationInviteResponse(organization.getId(), organization.getInviteCode());
	}

	@Transactional
	public OrganizationInviteResponse regenerateInviteCode(UUID organizationId, UUID actorId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!isFounder(organization, actorId) || actor.getStatus() != UserAccountStatus.APPROVED) {
			throw new InvalidRequestException("Only the approved founder can regenerate the invite code");
		}

		String current = organization.getInviteCode();
		String inviteCode = inviteCodeGenerator.nextUnique(
				code -> code.equals(current) || organizationRepository.existsByInviteCode(code));
		organization.setInviteCode(inviteCode);
		organizationRepository.save(organization);
		return new OrganizationInviteResponse(organization.getId(), inviteCode);
	}

	private static boolean isAdmin(AppUser actor) {
		return actor.getRole() == UserRole.ADMIN;
	}

	private static boolean isFounder(Organization organization, UUID actorId) {
		return organization.getFounder() != null && actorId.equals(organization.getFounder().getId());
	}
}
