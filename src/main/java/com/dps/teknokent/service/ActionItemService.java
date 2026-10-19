package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.dto.requests.CreateActionItemRequest;
import com.dps.teknokent.dto.requests.UpdateActionItemRequest;
import com.dps.teknokent.dto.responses.ActionItemResponse;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ActionItemService {

	private final ActionItemRepository actionItemRepository;
	private final DomainLookupService domainLookupService;
	private final NotificationService notificationService;

	@Transactional(readOnly = true)
	public UUID resolveOrganizationId(UUID organizationId, UUID mentorshipRequestId) {
		if ((organizationId == null) == (mentorshipRequestId == null)) {
			throw new InvalidRequestException(
					"Provide exactly one of organizationId or mentorshipRequestId");
		}
		return organizationId != null
				? organizationId
				: domainLookupService.requireMentorshipRequest(mentorshipRequestId).getOrganization().getId();
	}

	@Transactional(readOnly = true)
	public List<ActionItemResponse> findByScope(UUID organizationId, UUID mentorshipRequestId) {
		return actionItemRepository
				.findByOrganizationIdOrderByCreatedAtDesc(resolveOrganizationId(organizationId, mentorshipRequestId))
				.stream()
				.map(ActionItemResponse::fromEntity)
				.toList();
	}

	@Transactional
	public ActionItemResponse create(CreateActionItemRequest request) {
		Organization organization = domainLookupService.requireOrganization(
				resolveOrganizationId(request.organizationId(), request.mentorshipRequestId()));
		Meeting meeting = request.meetingId() == null ? null : domainLookupService.requireMeeting(request.meetingId());
		if (meeting != null && !meeting.getOrganization().getId().equals(organization.getId())) {
			throw new InvalidRequestException("Meeting and action item must belong to the same organization");
		}

		ActionItem actionItem = ActionItem.fromRequest(request, organization, meeting,
				domainLookupService.requireUser(request.ownerId()));
		return ActionItemResponse.fromEntity(actionItemRepository.saveAndFlush(actionItem));
	}

	@Transactional
	public ActionItemResponse update(
			UUID id, UUID organizationId, UUID mentorshipRequestId, UpdateActionItemRequest request) {
		ActionItem actionItem = actionItemRepository
				.findByIdAndOrganizationId(id, resolveOrganizationId(organizationId, mentorshipRequestId))
				.orElseThrow(() -> new ResourceNotFoundException("Action item not found: " + id));

		boolean wasCompleted = actionItem.getStatus() == ActionItemStatus.COMPLETED;
		actionItem.apply(request,
				request.ownerId() == null ? null : domainLookupService.requireUser(request.ownerId()));
		ActionItem saved = actionItemRepository.saveAndFlush(actionItem);
		if (!wasCompleted && saved.getStatus() == ActionItemStatus.COMPLETED) {
			notifyMentorOfCompletion(saved, mentorshipRequestId);
		}
		return ActionItemResponse.fromEntity(saved);
	}

	// The mentor who set the task hears when the startup finishes it.
	private void notifyMentorOfCompletion(ActionItem item, UUID mentorshipRequestId) {
		AppUser mentor = mentorshipRequestId != null
				? domainLookupService.requireMentorshipRequest(mentorshipRequestId).getMentor()
				: item.getMeeting() == null ? null : item.getMeeting().getMentor();
		if (mentor == null) {
			return;
		}
		notificationService.create(
				mentor,
				NotificationCategory.STARTUP_ACTIVITY,
				Texts.get(mentor, "notification.taskCompleted.title"),
				Texts.get(mentor, "notification.taskCompleted.body", item.getOrganization().getName(), item.getTitle()),
				"/mentor-dashboard");
	}
}
