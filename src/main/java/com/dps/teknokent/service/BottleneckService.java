package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateBottleneckRequest;
import com.dps.teknokent.dto.requests.UpdateBottleneckRequest;
import com.dps.teknokent.dto.responses.BottleneckResponse;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.BottleneckRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BottleneckService {

	private final BottleneckRepository bottleneckRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<BottleneckResponse> findByOrganization(UUID organizationId) {
		domainLookupService.requireOrganization(organizationId);
		return bottleneckRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
				.map(BottleneckResponse::fromEntity)
				.toList();
	}

	@Transactional
	public BottleneckResponse create(UUID organizationId, CreateBottleneckRequest request) {
		Bottleneck bottleneck = Bottleneck.fromRequest(request,
				domainLookupService.requireOrganization(organizationId),
				request.ownerId() == null ? null : domainLookupService.requireUser(request.ownerId()));
		return BottleneckResponse.fromEntity(bottleneckRepository.saveAndFlush(bottleneck));
	}

	@Transactional
	public BottleneckResponse update(UUID id, UpdateBottleneckRequest request) {
		Bottleneck bottleneck = bottleneckRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Bottleneck not found: " + id));

		bottleneck.apply(request,
				request.ownerId() == null ? null : domainLookupService.requireUser(request.ownerId()));
		return BottleneckResponse.fromEntity(bottleneckRepository.saveAndFlush(bottleneck));
	}
}
