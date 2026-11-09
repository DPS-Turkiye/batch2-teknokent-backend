package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AssignMentorshipTaskRequest;
import com.dps.teknokent.dto.requests.CreateActionItemRequest;
import com.dps.teknokent.dto.responses.ActionItemResponse;
import com.dps.teknokent.dto.responses.TaskAssigneeResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MentorshipTaskService {

	private final DomainLookupService lookup;
	private final AppUserRepository users;
	private final ActionItemService actionItems;

	@Transactional(readOnly = true)
	public List<TaskAssigneeResponse> assignees(UUID actorId, UUID requestId) {
		MentorshipRequest request = requireAcceptedMentor(actorId, requestId);
		return users.findByOrganizationIdOrderByFullNameAsc(request.getOrganization().getId()).stream()
				.filter(MentorshipTaskService::canReceiveTask)
				.map(user -> new TaskAssigneeResponse(user.getId(), user.getFullName()))
				.toList();
	}

	@Transactional
	public ActionItemResponse assign(UUID actorId, UUID requestId, AssignMentorshipTaskRequest input) {
		MentorshipRequest request = requireAcceptedMentor(actorId, requestId);
		AppUser owner = lookup.requireUser(input.ownerId());
		if (!canReceiveTask(owner) || owner.getOrganization() == null
				|| !owner.getOrganization().getId().equals(request.getOrganization().getId())) {
			throw new InvalidRequestException("Choose an approved member of this startup");
		}
		// The selected request supplies the scope; the browser cannot assign into another startup or meeting.
		return actionItems.create(new CreateActionItemRequest(null, request.getId(), null, owner.getId(),
				input.title(), input.description(), input.dueDate()));
	}

	private MentorshipRequest requireAcceptedMentor(UUID actorId, UUID requestId) {
		AppUser actor = lookup.requireUser(actorId);
		MentorshipRequest request = lookup.requireMentorshipRequest(requestId);
		if (!actor.has(UserRole.MENTOR) || actor.getStatus() != UserAccountStatus.APPROVED
				|| !request.getMentor().getId().equals(actorId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the assigned mentor can assign tasks");
		}
		if (request.getStatus() != MentorshipRequestStatus.ACCEPTED) {
			throw new InvalidRequestException("Tasks can only be assigned to accepted requests");
		}
		return request;
	}

	private static boolean canReceiveTask(AppUser user) {
		return user.has(UserRole.MENTEE) && user.getStatus() == UserAccountStatus.APPROVED;
	}
}
