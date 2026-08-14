package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
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
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public ActionItemResponse create(CreateActionItemRequest request) {
		Organization organization = domainLookupService.requireOrganization(request.organizationId());
		Meeting meeting = request.meetingId() == null ? null : domainLookupService.requireMeeting(request.meetingId());
		if (meeting != null && !meeting.getOrganization().getId().equals(organization.getId())) {
			throw new InvalidRequestException("Meeting and action item must belong to the same organization");
		}

		ActionItem actionItem = new ActionItem();
		actionItem.setOrganization(organization);
		actionItem.setMeeting(meeting);
		actionItem.setOwner(domainLookupService.requireUser(request.ownerId()));
		actionItem.setTitle(request.title().trim());
		actionItem.setDescription(request.description());
		actionItem.setStatus(ActionItemStatus.OPEN);
		actionItem.setDueDate(request.dueDate());
		return toResponse(actionItemRepository.saveAndFlush(actionItem));
	}

	@Transactional
	public ActionItemResponse update(UUID id, UpdateActionItemRequest request) {
		ActionItem actionItem = actionItemRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Action item not found: " + id));

		if (request.ownerId() != null) {
			actionItem.setOwner(domainLookupService.requireUser(request.ownerId()));
		}
		if (request.title() != null) {
			actionItem.setTitle(request.title().trim());
		}
		if (request.description() != null) {
			actionItem.setDescription(request.description());
		}
		if (request.status() != null) {
			actionItem.setStatus(request.status());
		}
		if (request.dueDate() != null) {
			actionItem.setDueDate(request.dueDate());
		}
		if (request.completionNotes() != null) {
			actionItem.setCompletionNotes(request.completionNotes());
		}

		return toResponse(actionItemRepository.saveAndFlush(actionItem));
	}

	private ActionItemResponse toResponse(ActionItem actionItem) {
		return new ActionItemResponse(
				actionItem.getId(),
				actionItem.getOrganization().getId(),
				actionItem.getOrganization().getName(),
				actionItem.getMeeting() == null ? null : actionItem.getMeeting().getId(),
				actionItem.getOwner().getId(),
				actionItem.getOwner().getFullName(),
				actionItem.getTitle(),
				actionItem.getDescription(),
				actionItem.getStatus(),
				actionItem.getDueDate(),
				actionItem.getCompletionNotes(),
				actionItem.getCreatedAt(),
				actionItem.getUpdatedAt());
	}
}
