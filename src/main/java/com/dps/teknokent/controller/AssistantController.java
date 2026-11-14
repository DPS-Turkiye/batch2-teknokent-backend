package com.dps.teknokent.controller;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.BotStateResponse;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AssistantService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Each call acts for the user behind the verified bearer token and only on a meeting they take part in (or as an
 * admin); the proxy's shared credentials name nobody and are refused. The checks live in {@link AssistantService}.
 * A failing meeting provider is a 502 with a fixed text (see {@code GlobalExceptionHandler}).
 */
@RestController
@RequiredArgsConstructor
public class AssistantController {

	private final AssistantService assistantService;

	@PostMapping("/api/assistant/bots")
	@ResponseStatus(HttpStatus.CREATED)
	public AddBotResponse addBot(
			@AuthenticationPrincipal AppUserPrincipal caller,
			@Valid @RequestBody AddBotRequest request) {
		return assistantService.addAdHocBot(idOf(caller), request);
	}

	@GetMapping("/api/assistant/transcripts/{platform}/{nativeMeetingId}")
	public TranscriptResponse getTranscript(
			@AuthenticationPrincipal AppUserPrincipal caller,
			@PathVariable String platform,
			@PathVariable String nativeMeetingId) {
		return assistantService.getTranscript(idOf(caller), platform, nativeMeetingId);
	}

	@GetMapping("/api/assistant/bots/{platform}/{nativeMeetingId}")
	public BotStateResponse getBotState(
			@AuthenticationPrincipal AppUserPrincipal caller,
			@PathVariable String platform,
			@PathVariable String nativeMeetingId) {
		return assistantService.botState(idOf(caller), platform, nativeMeetingId);
	}

	@DeleteMapping("/api/assistant/bots/{platform}/{nativeMeetingId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void stopBot(
			@AuthenticationPrincipal AppUserPrincipal caller,
			@PathVariable String platform,
			@PathVariable String nativeMeetingId) {
		assistantService.stopBot(idOf(caller), platform, nativeMeetingId);
	}

	@PostMapping("/api/assistant/bots/{platform}/{nativeMeetingId}/report")
	public MeetingReportResponse generateMeetingReport(
			@AuthenticationPrincipal AppUserPrincipal caller,
			@PathVariable String platform,
			@PathVariable String nativeMeetingId) {
		return assistantService.generateMeetingReport(idOf(caller), platform, nativeMeetingId);
	}

	private static @Nullable UUID idOf(@Nullable AppUserPrincipal caller) {
		return caller == null ? null : caller.id();
	}
}
