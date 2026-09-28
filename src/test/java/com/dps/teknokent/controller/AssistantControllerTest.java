package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AssistantService;
import com.dps.teknokent.service.MeetingService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssistantController.class)
@Import(SecurityConfig.class)
class AssistantControllerTest {
  private static final UUID ACTOR_ID = UUID.fromString("a3eaa8dc-f680-41fc-b0e1-63323e8e9eb6");
  private static final UUID SESSION_ID = UUID.fromString("8703d8c7-a0b6-4b05-9bb2-1d58aeb4e719");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AssistantService assistantService;
  @MockitoBean private MeetingService meetingService;

  @Test
  void addBotReturnsOpaqueSessionIdAndDoesNotExposeProviderId() throws Exception {
    when(assistantService.createSession(any(AddBotRequest.class), eq(ACTOR_ID)))
        .thenReturn(new AddBotResponse(SESSION_ID.toString(), SESSION_ID.toString(),
            "google_meet", "joining", "Mentor Bot"));

    mockMvc.perform(post("/api/assistant/sessions")
            .with(asUser())
            .contentType("application/json")
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
        .andExpect(jsonPath("$.assistantSessionId").value(SESSION_ID.toString()))
        .andExpect(jsonPath("$.nativeMeetingId").doesNotExist())
        .andExpect(jsonPath("$.providerBotId").doesNotExist());
  }

  @Test
  void unauthenticatedSessionCreationIsRejected() throws Exception {
    mockMvc.perform(post("/api/assistant/sessions")
            .contentType("application/json")
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isUnauthorized());
    verify(assistantService, never()).createSession(any(), any());
  }

  @Test
  void transcriptUsesOpaqueSessionAndRejectsNonOwner() throws Exception {
    when(assistantService.getTranscript(SESSION_ID, ACTOR_ID))
        .thenThrow(new org.springframework.security.access.AccessDeniedException("forbidden"));

    mockMvc.perform(get("/api/assistant/sessions/{id}/transcript", SESSION_ID).with(asUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void authorizedTranscriptUsesSessionId() throws Exception {
    when(assistantService.getTranscript(SESSION_ID, ACTOR_ID))
        .thenReturn(new TranscriptResponse(List.of(
            new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "01:05"))));

    mockMvc.perform(get("/api/assistant/sessions/{id}/transcript", SESSION_ID).with(asUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.segments[0].text").value("Merhaba"));
    verify(assistantService).getTranscript(SESSION_ID, ACTOR_ID);
  }

  @Test
  void stopUsesOpaqueSessionAndUpdatesScheduledMeeting() throws Exception {
    when(assistantService.stopSession(SESSION_ID, ACTOR_ID)).thenReturn(SESSION_ID);

    mockMvc.perform(delete("/api/assistant/sessions/{id}", SESSION_ID).with(asUser()))
        .andExpect(status().isNoContent());
    verify(meetingService).markScheduledBotStopped(SESSION_ID);
  }

  private static RequestPostProcessor asUser() {
    var principal = new AppUserPrincipal(ACTOR_ID, "user@example.com", UserRole.MENTOR,
        UserAccountStatus.APPROVED, "firebase-uid");
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        principal, "firebase", List.of(new SimpleGrantedAuthority("ROLE_MENTOR")));
    return authentication(authentication);
  }
}
