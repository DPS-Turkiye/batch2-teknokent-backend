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

	@Transactional(readOnly = true)
	public List<ActionItemResponse> findByScopeForActor(
			UUID actorId, UUID organizationId, UUID mentorshipRequestId) {
		UUID resolvedOrganizationId = resolveOrganizationId(organizationId, mentorshipRequestId);
		requireScopeParticipant(actorId, resolvedOrganizationId, mentorshipRequestId);
		if (mentorshipRequestId != null) {
			return actionItemRepository
					.findByOrganizationIdAndMentorshipRequest_IdOrderByCreatedAtDesc(
							resolvedOrganizationId, mentorshipRequestId)
					.stream().map(ActionItemResponse::fromEntity).toList();
		}
		return findByScope(organizationId, mentorshipRequestId);
	}

	@Transactional
	public ActionItemResponse create(CreateActionItemRequest request) {
		Organization organization = domainLookupService.requireOrganization(
				resolveOrganizationId(request.organizationId(), request.mentorshipRequestId()));
		Meeting meeting = request.meetingId() == null ? null : domainLookupService.requireMeeting(request.meetingId());
		if (meeting != null && !meeting.getOrganization().getId().equals(organization.getId())) {
			throw new InvalidRequestException("Meeting and action item must belong to the same organization");
		}

		var mentorshipRequest = request.mentorshipRequestId() == null ? null
				: domainLookupService.requireMentorshipRequest(request.mentorshipRequestId());
		if (mentorshipRequest != null && !mentorshipRequest.getOrganization().getId().equals(organization.getId())) {
			throw new InvalidRequestException("Mentorship request and action item must belong to the same organization");
		}
		if (meeting != null && mentorshipRequest != null
				&& (meeting.getMentorshipRequest() == null
						|| !meeting.getMentorshipRequest().getId().equals(mentorshipRequest.getId()))) {
			throw new InvalidRequestException("Meeting and action item must belong to the same mentorship request");
		}
		ActionItem actionItem = ActionItem.fromRequest(request, organization, meeting, mentorshipRequest,
				domainLookupService.requireUser(request.ownerId()));
		return ActionItemResponse.fromEntity(actionItemRepository.saveAndFlush(actionItem));
	}

	@Transactional
	public ActionItemResponse createForActor(UUID actorId, CreateActionItemRequest request) {
		UUID organizationId = resolveOrganizationId(request.organizationId(), request.mentorshipRequestId());
		requireScopeParticipant(actorId, organizationId, request.mentorshipRequestId());
		AppUser owner = domainLookupService.requireUser(request.ownerId());
		if (owner.getOrganization() == null || !owner.getOrganization().getId().equals(organizationId)) {
			throw new InvalidRequestException("Action item owner must belong to the organization");
		}
		return create(request);
	}

	@Transactional
	public ActionItemResponse update(
			UUID id, UUID organizationId, UUID mentorshipRequestId, UpdateActionItemRequest request) {
		ActionItem actionItem = actionItemRepository
				.findByIdAndOrganizationId(id, resolveOrganizationId(organizationId, mentorshipRequestId))
				.orElseThrow(() -> new ResourceNotFoundException("Action item not found: " + id));
		return applyUpdate(id, mentorshipRequestId, request, actionItem);
	}

	private ActionItemResponse applyUpdate(
			UUID id, UUID mentorshipRequestId, UpdateActionItemRequest request, ActionItem actionItem) {
		boolean wasCompleted = actionItem.getStatus() == ActionItemStatus.COMPLETED;
		actionItem.apply(request,
				request.ownerId() == null ? null : domainLookupService.requireUser(request.ownerId()));
		ActionItem saved = actionItemRepository.saveAndFlush(actionItem);
		if (!wasCompleted && saved.getStatus() == ActionItemStatus.COMPLETED) {
			notifyMentorOfCompletion(saved, mentorshipRequestId);
		}
		return ActionItemResponse.fromEntity(saved);
	}

	@Transactional
	public ActionItemResponse updateForActor(
			UUID actorId, UUID id, UUID organizationId, UUID mentorshipRequestId, UpdateActionItemRequest request) {
		UUID resolvedOrganizationId = resolveOrganizationId(organizationId, mentorshipRequestId);
		requireScopeParticipant(actorId, resolvedOrganizationId, mentorshipRequestId);
		if (request.ownerId() != null) {
			AppUser owner = domainLookupService.requireUser(request.ownerId());
			if (owner.getOrganization() == null || !owner.getOrganization().getId().equals(resolvedOrganizationId)) {
				throw new InvalidRequestException("Action item owner must belong to the organization");
			}
		}
		ActionItem actionItem = actionItemRepository
				.findByIdAndOrganizationIdAndMentorshipRequest_Id(id, resolvedOrganizationId, mentorshipRequestId)
				.orElseThrow(() -> new ResourceNotFoundException("Action item not found: " + id));
		return applyUpdate(id, mentorshipRequestId, request, actionItem);
	}

	private void requireScopeParticipant(UUID actorId, UUID organizationId, UUID mentorshipRequestId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (mentorshipRequestId != null) {
			var request = domainLookupService.requireMentorshipRequest(mentorshipRequestId);
			boolean organizationMember = actor.getOrganization() != null
					&& actor.getOrganization().getId().equals(request.getOrganization().getId());
			if (!organizationMember && !request.getMentor().getId().equals(actorId)) {
				throw new ResourceNotFoundException("Action item scope not found");
			}
			return;
		}
		if (actor.getOrganization() == null || !actor.getOrganization().getId().equals(organizationId)) {
			throw new ResourceNotFoundException("Action item scope not found");
		}
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
				"Görev tamamlandı",
				item.getOrganization().getName() + " «" + item.getTitle() + "» görevini tamamladı.",
				"/mentor-dashboard");
	}
}
