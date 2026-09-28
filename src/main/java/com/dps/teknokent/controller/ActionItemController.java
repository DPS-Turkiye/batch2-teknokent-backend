package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateActionItemRequest;
import com.dps.teknokent.dto.requests.UpdateActionItemRequest;
import com.dps.teknokent.dto.responses.ActionItemResponse;
import com.dps.teknokent.service.ActionItemService;
import com.dps.teknokent.security.CurrentActor;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/actions")
@RequiredArgsConstructor
@Tag(name = "Action Items")
public class ActionItemController {

	private final ActionItemService actionItemService;

	@GetMapping
	public List<ActionItemResponse> findByScope(
			@RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID mentorshipRequestId) {
		return actionItemService.findByScopeForActor(
				CurrentActor.resolve(actorId), organizationId, mentorshipRequestId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ActionItemResponse create(
			@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody CreateActionItemRequest request) {
		return actionItemService.createForActor(CurrentActor.resolve(actorId), request);
	}

	@PatchMapping("/{id}")
	public ActionItemResponse update(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID mentorshipRequestId,
			@Valid @RequestBody UpdateActionItemRequest request) {
		return actionItemService.updateForActor(
				CurrentActor.resolve(actorId), id, organizationId, mentorshipRequestId, request);
	}
}
