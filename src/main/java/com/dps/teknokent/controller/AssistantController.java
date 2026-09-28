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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.requests.GenerateMeetingReportRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.ErrorResponse;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.AssistantSessionResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.service.AssistantService;
import com.dps.teknokent.service.MeetingService;
import com.dps.teknokent.security.AppUserPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AssistantController {

  private final AssistantService assistantService;
  private final MeetingService meetingService;

  @PostMapping("/api/assistant/sessions")
  public ResponseEntity<?> addBot(@Valid @RequestBody AddBotRequest request) {
    try {
      AddBotResponse response = assistantService.createSession(request, currentActorId());
      return ResponseEntity.status(HttpStatus.CREATED).body(response);
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    } catch (RestClientResponseException ex) {
      return ResponseEntity.status(ex.getStatusCode())
          .body(new ErrorResponse(ex.getResponseBodyAsString()));
    } catch (ResponseStatusException ex) {
      return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(ex.getReason()));
    } catch (Exception ex) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(new ErrorResponse("Failed to reach meeting provider: " + ex.getMessage()));
    }
  }

  @GetMapping("/api/assistant/meetings/{meetingId}/session")
  public ResponseEntity<?> getScheduledSession(@PathVariable java.util.UUID meetingId) {
    try {
      AssistantSessionResponse response = assistantService.getScheduledSession(meetingId, currentActorId());
      return ResponseEntity.ok(response);
    } catch (AccessDeniedException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(ex.getMessage()));
    } catch (ResponseStatusException ex) {
      return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(ex.getReason()));
    }
  }

  @GetMapping("/api/assistant/sessions/{sessionId}/transcript")
  public ResponseEntity<?> getTranscript(
      @PathVariable java.util.UUID sessionId) {
    try {
      return ResponseEntity.ok(assistantService.getTranscript(sessionId, currentActorId()));
    } catch (AccessDeniedException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(ex.getMessage()));
    } catch (ResponseStatusException ex) {
      return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(ex.getReason()));
    } catch (RestClientResponseException ex) {
      return ResponseEntity.status(ex.getStatusCode())
          .body(new ErrorResponse(ex.getResponseBodyAsString()));
    } catch (Exception ex) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(new ErrorResponse("Failed to fetch transcript: " + ex.getMessage()));
    }
  }

  @DeleteMapping("/api/assistant/sessions/{sessionId}")
  public ResponseEntity<?> stopBot(
      @PathVariable java.util.UUID sessionId) {
    try {
      java.util.UUID meetingId = assistantService.stopSession(sessionId, currentActorId());
      if (meetingId != null) meetingService.markScheduledBotStopped(meetingId);
      return ResponseEntity.noContent().build();
    } catch (AccessDeniedException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(ex.getMessage()));
    } catch (ResponseStatusException ex) {
      return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(ex.getReason()));
    } catch (RestClientResponseException ex) {
      return ResponseEntity.status(ex.getStatusCode())
          .body(new ErrorResponse(ex.getResponseBodyAsString()));
    } catch (Exception ex) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(new ErrorResponse("Failed to stop bot: " + ex.getMessage()));
    }
  }

  @PostMapping("/api/assistant/sessions/{sessionId}/report")
  public ResponseEntity<?> generateMeetingReport(
      @PathVariable java.util.UUID sessionId,
      @RequestBody(required = false) GenerateMeetingReportRequest request) {
    try {
      MeetingReportResponse response =
          assistantService.generateMeetingReport(sessionId, currentActorId(), request);
      return ResponseEntity.ok(response);
    } catch (AccessDeniedException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(ex.getMessage()));
    } catch (AssistantService.ReportLimitExceededException ex) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(ex.getMessage()));
    } catch (InvalidRequestException ex) {
      return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    } catch (RestClientResponseException ex) {
      return ResponseEntity.status(ex.getStatusCode())
          .body(new ErrorResponse(ex.getResponseBodyAsString()));
    } catch (Exception ex) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(new ErrorResponse("Failed to generate meeting report: " + ex.getMessage()));
    }
  }

  private static java.util.UUID currentActorId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
      return principal.id();
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
  }
}
