package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateProgressUpdateRequest;
import com.dps.teknokent.dto.responses.ProgressUpdateResponse;
import com.dps.teknokent.entity.ProgressUpdate;
import com.dps.teknokent.repository.ProgressUpdateRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProgressUpdateService {

	private final ProgressUpdateRepository progressUpdateRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<ProgressUpdateResponse> findByOrganization(UUID organizationId) {
		domainLookupService.requireOrganization(organizationId);
		return progressUpdateRepository.findByOrganizationIdOrderByRecordedAtDesc(organizationId).stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public ProgressUpdateResponse create(UUID organizationId, CreateProgressUpdateRequest request) {
		ProgressUpdate update = new ProgressUpdate();
		update.setOrganization(domainLookupService.requireOrganization(organizationId));
		update.setAuthor(domainLookupService.requireUser(request.authorId()));
		update.setCategory(request.category().trim());
		update.setStatus(request.status());
		update.setSummary(request.summary().trim());
		update.setEvidence(request.evidence());
		update.setRecordedAt(request.recordedAt());
		return toResponse(progressUpdateRepository.saveAndFlush(update));
	}

	private ProgressUpdateResponse toResponse(ProgressUpdate update) {
		return new ProgressUpdateResponse(
				update.getId(),
				update.getOrganization().getId(),
				update.getAuthor().getId(),
				update.getAuthor().getFullName(),
				update.getCategory(),
				update.getStatus(),
				update.getSummary(),
				update.getEvidence(),
				update.getRecordedAt(),
				update.getCreatedAt());
	}
}
