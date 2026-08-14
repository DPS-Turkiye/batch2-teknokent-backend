package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.dto.responses.OrganizationResponse;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrganizationService {

	private final OrganizationRepository organizationRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<OrganizationResponse> findAll() {
		return organizationRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public OrganizationResponse findById(UUID id) {
		return toResponse(domainLookupService.requireOrganization(id));
	}

	@Transactional
	public OrganizationResponse create(CreateOrganizationRequest request) {
		Organization organization = new Organization();
		organization.setName(request.name().trim());
		organization.setDescription(request.description());
		return toResponse(organizationRepository.saveAndFlush(organization));
	}

	private OrganizationResponse toResponse(Organization organization) {
		return new OrganizationResponse(
				organization.getId(),
				organization.getName(),
				organization.getDescription(),
				organization.getCreatedAt(),
				organization.getUpdatedAt());
	}
}
