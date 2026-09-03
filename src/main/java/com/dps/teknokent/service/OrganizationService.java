package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationRequest;
import com.dps.teknokent.dto.responses.OrganizationInviteResponse;
import com.dps.teknokent.dto.responses.OrganizationResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.InviteCodeGenerator;
import com.dps.teknokent.util.TextUtils;

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

	/**
	 * The founder editing their own startup profile after signup. Only the founder
	 * may write it — an admin can read the organization, but rewriting a startup's
	 * own description is not theirs to do.
	 *
	 * <p>
	 * Deliberately not gated on {@code APPROVED}, unlike
	 * {@link #regenerateInviteCode}: a founder waiting on coordinator review is
	 * exactly who needs to fix a typo or add the detail the coordinator asked for.
	 */
	@Transactional
	public OrganizationResponse update(UUID organizationId, UpdateOrganizationRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		domainLookupService.requireUser(request.actorId());
		if (!isFounder(organization, request.actorId())) {
			throw new InvalidRequestException("Only the organization founder can edit the startup profile");
		}

		organization.setName(TextUtils.trim(request.name()));
		organization.setDescription(TextUtils.normalizeOptional(request.description()));
		organization.setWebsite(TextUtils.normalizeOptional(request.website()));
		organization.setProblem(TextUtils.normalizeOptional(request.problem()));
		organization.setSolution(TextUtils.normalizeOptional(request.solution()));
		organization.setCurrentSolution(TextUtils.normalizeOptional(request.currentSolution()));
		organization.setTargetUser(TextUtils.normalizeOptional(request.targetUser()));
		organization.setSector(TextUtils.normalizeOptional(request.sector()));
		organization.setMaturity(TextUtils.normalizeOptional(request.maturity()));
		organization.setProgramLevel(TextUtils.normalizeOptional(request.programLevel()));
		// Mutated in place rather than replaced: Hibernate tracks the collection
		// instance it loaded, and swapping it for a fresh list on a managed entity
		// makes it clear() the old one during merge.
		organization.getSupportAreas().clear();
		organization.getSupportAreas().addAll(TextUtils.normalizedList(request.supportAreas()));

		return OrganizationResponse.fromEntity(organizationRepository.save(organization));
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
