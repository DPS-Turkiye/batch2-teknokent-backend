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
}
