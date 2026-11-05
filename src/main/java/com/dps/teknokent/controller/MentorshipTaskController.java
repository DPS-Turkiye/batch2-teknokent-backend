package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.AssignMentorshipTaskRequest;
import com.dps.teknokent.dto.responses.ActionItemResponse;
import com.dps.teknokent.dto.responses.TaskAssigneeResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.MentorshipTaskService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/mentorship-requests/{requestId}")
@RequiredArgsConstructor
public class MentorshipTaskController {

	private final MentorshipTaskService tasks;

	@GetMapping("/task-assignees")
	public List<TaskAssigneeResponse> assignees(@PathVariable UUID requestId,
			@RequestParam(required = false) UUID actorId) {
		return tasks.assignees(CurrentActor.resolve(actorId), requestId);
	}

	@PostMapping("/tasks")
	@ResponseStatus(HttpStatus.CREATED)
	public ActionItemResponse assign(@PathVariable UUID requestId,
			@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody AssignMentorshipTaskRequest request) {
		return tasks.assign(CurrentActor.resolve(actorId), requestId, request);
	}
}
