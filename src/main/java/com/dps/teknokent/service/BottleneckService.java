package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.BottleneckStatus;
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
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public BottleneckResponse create(UUID organizationId, CreateBottleneckRequest request) {
		Bottleneck bottleneck = new Bottleneck();
		bottleneck.setOrganization(domainLookupService.requireOrganization(organizationId));
		if (request.ownerId() != null) {
			bottleneck.setOwner(domainLookupService.requireUser(request.ownerId()));
		}
		bottleneck.setTitle(request.title().trim());
		bottleneck.setDescription(request.description());
		bottleneck.setSeverity(request.severity());
		bottleneck.setStatus(BottleneckStatus.OPEN);
		bottleneck.setTargetResolutionDate(request.targetResolutionDate());
		return toResponse(bottleneckRepository.saveAndFlush(bottleneck));
	}

	@Transactional
	public BottleneckResponse update(UUID id, UpdateBottleneckRequest request) {
		Bottleneck bottleneck = bottleneckRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Bottleneck not found: " + id));

		if (request.ownerId() != null) {
			bottleneck.setOwner(domainLookupService.requireUser(request.ownerId()));
		}
		if (request.title() != null) {
			bottleneck.setTitle(request.title().trim());
		}
		if (request.description() != null) {
			bottleneck.setDescription(request.description());
		}
		if (request.severity() != null) {
			bottleneck.setSeverity(request.severity());
		}
		if (request.status() != null) {
			bottleneck.setStatus(request.status());
		}
		if (request.targetResolutionDate() != null) {
			bottleneck.setTargetResolutionDate(request.targetResolutionDate());
		}
		if (request.resolutionNotes() != null) {
			bottleneck.setResolutionNotes(request.resolutionNotes());
		}

		return toResponse(bottleneckRepository.saveAndFlush(bottleneck));
	}

	private BottleneckResponse toResponse(Bottleneck bottleneck) {
		return new BottleneckResponse(
				bottleneck.getId(),
				bottleneck.getOrganization().getId(),
				bottleneck.getOwner() == null ? null : bottleneck.getOwner().getId(),
				bottleneck.getOwner() == null ? null : bottleneck.getOwner().getFullName(),
				bottleneck.getTitle(),
				bottleneck.getDescription(),
				bottleneck.getSeverity(),
				bottleneck.getStatus(),
				bottleneck.getTargetResolutionDate(),
				bottleneck.getResolutionNotes(),
				bottleneck.getCreatedAt(),
				bottleneck.getUpdatedAt());
	}
}
