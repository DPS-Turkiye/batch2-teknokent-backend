package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.SubmitPlatformFeedbackRequest;
import com.dps.teknokent.dto.responses.PlatformFeedbackInboxResponse;
import com.dps.teknokent.dto.responses.PlatformFeedbackResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.PlatformFeedbackService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Platform feedback")
public class PlatformFeedbackController {

	private final PlatformFeedbackService feedbackService;

	@PostMapping("/api/platform-feedback")
	@ResponseStatus(HttpStatus.CREATED)
	public PlatformFeedbackResponse submit(@Valid @RequestBody SubmitPlatformFeedbackRequest request) {
		return feedbackService.submit(CurrentActor.resolve(request.actorId()), request);
	}

	@GetMapping("/api/admin/platform-feedback")
	public PlatformFeedbackInboxResponse inbox(
			@RequestParam(required = false) UUID actorId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "false") boolean unreadOnly) {
		return feedbackService.inbox(CurrentActor.resolve(actorId), page, unreadOnly);
	}

	@PatchMapping("/api/admin/platform-feedback/{id}/read")
	public PlatformFeedbackResponse markRead(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return feedbackService.markRead(CurrentActor.resolve(actorId), id);
	}
}
