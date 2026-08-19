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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateActionItemRequest;
import com.dps.teknokent.dto.requests.UpdateActionItemRequest;
import com.dps.teknokent.dto.responses.ActionItemResponse;
import com.dps.teknokent.service.ActionItemService;

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
	public List<ActionItemResponse> findAll() {
		return actionItemService.findAll();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ActionItemResponse create(@Valid @RequestBody CreateActionItemRequest request) {
		return actionItemService.create(request);
	}

	@PatchMapping("/{id}")
	public ActionItemResponse update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateActionItemRequest request) {
		return actionItemService.update(id, request);
	}
}
