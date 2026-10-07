package com.dps.teknokent.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.requests.GenerateMeetingReportRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.ErrorResponse;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.service.AssistantService;
import com.dps.teknokent.service.MeetingService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AssistantController {

  private final AssistantService assistantService;
  private final MeetingService meetingService;

  @PostMapping("/api/assistant/bots")
  public ResponseEntity<?> addBot(@Valid @RequestBody AddBotRequest request) {
    try {
      AddBotResponse response = assistantService.addBot(request);
      return ResponseEntity.status(HttpStatus.CREATED).body(response);
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    } catch (Exception ex) {
      return providerFailure("Failed to reach meeting provider", ex);
    }
  }

  @GetMapping("/api/assistant/transcripts/{platform}/{nativeMeetingId}")
  public ResponseEntity<?> getTranscript(
      @PathVariable String platform,
      @PathVariable String nativeMeetingId) {
    try {
      return ResponseEntity.ok(
          assistantService.getTranscript(platform, nativeMeetingId));
    } catch (Exception ex) {
      return providerFailure("Failed to fetch transcript", ex);
    }
  }

  @DeleteMapping("/api/assistant/bots/{platform}/{nativeMeetingId}")
  public ResponseEntity<?> stopBot(
      @PathVariable String platform,
      @PathVariable String nativeMeetingId) {
    try {
      assistantService.stopBot(platform, nativeMeetingId);
      meetingService.markBotStopped(nativeMeetingId);
      return ResponseEntity.noContent().build();
    } catch (Exception ex) {
      return providerFailure("Failed to stop bot", ex);
    }
  }

  @PostMapping("/api/assistant/bots/{platform}/{nativeMeetingId}/report")
  public ResponseEntity<?> generateMeetingReport(
      @PathVariable String platform,
      @PathVariable String nativeMeetingId,
      @RequestBody(required = false) GenerateMeetingReportRequest request) {
    try {
      MeetingReportResponse response =
          assistantService.generateMeetingReport(platform, nativeMeetingId, request);
      return ResponseEntity.ok(response);
    } catch (InvalidRequestException ex) {
      return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    } catch (Exception ex) {
      return providerFailure("Failed to generate meeting report", ex);
    }
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
