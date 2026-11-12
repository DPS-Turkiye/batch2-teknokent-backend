package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.ErrorResponse;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.exception.AiFeatureDisabledException;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AssistantService;
import com.dps.teknokent.service.MeetingService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Each call acts for the user behind the verified bearer token and only on a meeting they take part in (or as an
 * admin); the proxy's shared credentials name nobody and are refused. The checks live in {@link AssistantService}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AssistantController {

  private final AssistantService assistantService;
  private final MeetingService meetingService;

  @PostMapping("/api/assistant/bots")
  public ResponseEntity<?> addBot(
      @AuthenticationPrincipal AppUserPrincipal caller,
      @Valid @RequestBody AddBotRequest request) {
    try {
      AddBotResponse response = assistantService.addAdHocBot(idOf(caller), request);
      return ResponseEntity.status(HttpStatus.CREATED).body(response);
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    } catch (ConflictException ex) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    } catch (ResponseStatusException | ResourceNotFoundException ex) {
      throw ex;
    } catch (Exception ex) {
      return providerFailure("Failed to reach meeting provider", ex);
    }
  }

  @GetMapping("/api/assistant/transcripts/{platform}/{nativeMeetingId}")
  public ResponseEntity<?> getTranscript(
      @AuthenticationPrincipal AppUserPrincipal caller,
      @PathVariable String platform,
      @PathVariable String nativeMeetingId) {
    try {
      return ResponseEntity.ok(
          assistantService.getTranscript(idOf(caller), platform, nativeMeetingId));
    } catch (ResponseStatusException | ResourceNotFoundException ex) {
      throw ex;
    } catch (Exception ex) {
      return providerFailure("Failed to fetch transcript", ex);
    }
  }

  @GetMapping("/api/assistant/bots/{platform}/{nativeMeetingId}")
  public ResponseEntity<?> getBotState(
      @AuthenticationPrincipal AppUserPrincipal caller,
      @PathVariable String platform,
      @PathVariable String nativeMeetingId) {
    try {
      return ResponseEntity.ok(assistantService.botState(idOf(caller), platform, nativeMeetingId));
    } catch (ResponseStatusException | ResourceNotFoundException ex) {
      throw ex;
    } catch (Exception ex) {
      return providerFailure("Failed to read bot state", ex);
    }
  }

  @DeleteMapping("/api/assistant/bots/{platform}/{nativeMeetingId}")
  public ResponseEntity<?> stopBot(
      @AuthenticationPrincipal AppUserPrincipal caller,
      @PathVariable String platform,
      @PathVariable String nativeMeetingId) {
    try {
      assistantService.stopBot(idOf(caller), platform, nativeMeetingId);
      meetingService.markBotStopped(nativeMeetingId);
      return ResponseEntity.noContent().build();
    } catch (ResponseStatusException | ResourceNotFoundException ex) {
      throw ex;
    } catch (Exception ex) {
      return providerFailure("Failed to stop bot", ex);
    }
  }

  @PostMapping("/api/assistant/bots/{platform}/{nativeMeetingId}/report")
  public ResponseEntity<?> generateMeetingReport(
      @AuthenticationPrincipal AppUserPrincipal caller,
      @PathVariable String platform,
      @PathVariable String nativeMeetingId) {
    try {
      MeetingReportResponse response =
          assistantService.generateMeetingReport(idOf(caller), platform, nativeMeetingId);
      return ResponseEntity.ok(response);
    } catch (InvalidRequestException ex) {
      return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    } catch (ResponseStatusException | ResourceNotFoundException | AiFeatureDisabledException ex) {
      throw ex;
    } catch (Exception ex) {
      return providerFailure("Failed to generate meeting report", ex);
    }
  }

  private static UUID idOf(AppUserPrincipal caller) {
    return caller == null ? null : caller.id();
  }

  /**
   * Provider bodies and exception messages carry internal hosts and the provider's own wording, so
   * the caller gets the status and a fixed message and the detail stays in the log.
   */
  private static ResponseEntity<ErrorResponse> providerFailure(String message, Exception ex) {
    if (ex instanceof RestClientResponseException response) {
      log.warn("{}: provider answered {} {}", message, response.getStatusCode(), response.getResponseBodyAsString());
      return ResponseEntity.status(response.getStatusCode()).body(new ErrorResponse(message));
    }
    log.error(message, ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(message));
  }
}
