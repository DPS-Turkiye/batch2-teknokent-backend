package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateActionItemRequest;
import com.dps.teknokent.dto.requests.UpdateActionItemRequest;
import com.dps.teknokent.dto.responses.ActionItemResponse;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ActionItemService {

	private final ActionItemRepository actionItemRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<ActionItemResponse> findAll() {
		return actionItemRepository.findAllByOrderByCreatedAtDesc().stream()
				.map(ActionItemResponse::fromEntity)
				.toList();
	}

	@Transactional
	public ActionItemResponse create(CreateActionItemRequest request) {
		Organization organization = domainLookupService.requireOrganization(request.organizationId());
		Meeting meeting = request.meetingId() == null ? null : domainLookupService.requireMeeting(request.meetingId());
		if (meeting != null && !meeting.getOrganization().getId().equals(organization.getId())) {
			throw new InvalidRequestException("Meeting and action item must belong to the same organization");
		}

		ActionItem actionItem = ActionItem.fromRequest(request, organization, meeting,
				domainLookupService.requireUser(request.ownerId()));
		return ActionItemResponse.fromEntity(actionItemRepository.saveAndFlush(actionItem));
	}

	@Transactional
	public ActionItemResponse update(UUID id, UpdateActionItemRequest request) {
		ActionItem actionItem = actionItemRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Action item not found: " + id));

		actionItem.apply(request,
				request.ownerId() == null ? null : domainLookupService.requireUser(request.ownerId()));
		return ActionItemResponse.fromEntity(actionItemRepository.saveAndFlush(actionItem));
	}
}
