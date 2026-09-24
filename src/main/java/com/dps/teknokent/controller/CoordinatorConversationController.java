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

import com.dps.teknokent.dto.requests.SendCoordinatorMessageRequest;
import com.dps.teknokent.dto.requests.StartCoordinatorConversationRequest;
import com.dps.teknokent.dto.responses.CoordinatorConversationResponse;
import com.dps.teknokent.dto.responses.CoordinatorMessageResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.CoordinatorConversationService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator-conversations")
@RequiredArgsConstructor
@Tag(name = "Coordinator conversations")
public class CoordinatorConversationController {

	private final CoordinatorConversationService conversationService;

	@GetMapping
	public List<CoordinatorConversationResponse> list(@RequestParam(required = false) UUID actorId) {
		return conversationService.list(CurrentActor.resolve(actorId));
	}

	@PostMapping
	public CoordinatorConversationResponse start(@Valid @RequestBody StartCoordinatorConversationRequest request) {
		return conversationService.start(CurrentActor.resolve(request.actorId()), request);
	}

	@GetMapping("/{id}/messages")
	public List<CoordinatorMessageResponse> messages(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return conversationService.messages(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/{id}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	public CoordinatorMessageResponse send(
			@PathVariable UUID id,
			@Valid @RequestBody SendCoordinatorMessageRequest request) {
		return conversationService.send(CurrentActor.resolve(request.actorId()), id, request);
	}
}
