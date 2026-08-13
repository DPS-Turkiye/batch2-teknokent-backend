package com.dps.teknokent.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.junit.jupiter.api.Test;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.service.AssistantService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssistantController.class)
@Import(SecurityConfig.class)
class AssistantControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AssistantService assistantService;

  @Test
  void addBotReturnsCreated() throws Exception {
    when(assistantService.addBot(any(AddBotRequest.class)))
        .thenReturn(new AddBotResponse(
            "bot-1",
            "google_meet",
            "abc-defg-hij",
            "joining",
            "Mentor Bot"
        ));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("bot-1"))
        .andExpect(jsonPath("$.platform").value("google_meet"))
        .andExpect(jsonPath("$.nativeMeetingId").value("abc-defg-hij"));
  }

  @Test
  void addBotReturnsBadRequestOnInvalidUrl() throws Exception {
    when(assistantService.addBot(any(AddBotRequest.class)))
        .thenThrow(new IllegalArgumentException("Unsupported meeting link"));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://zoom.us/j/1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Unsupported meeting link"));
  }

  @Test
  void addBotReturnsProviderStatusOnRestClientError() throws Exception {
    when(assistantService.addBot(any(AddBotRequest.class)))
        .thenThrow(HttpClientErrorException.create(
            HttpStatus.CONFLICT,
            "Conflict",
            HttpHeaders.EMPTY,
            "provider conflict".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8
        ));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("provider conflict"));
  }

  @Test
  void getTranscriptReturnsOk() throws Exception {
    when(assistantService.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new TranscriptResponse(List.of(
            new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "01:05")
        )));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.segments[0].speaker").value("Ayşe"))
        .andExpect(jsonPath("$.segments[0].text").value("Merhaba"))
        .andExpect(jsonPath("$.segments[0].at").value("01:05"));
  }

  @Test
  void getTranscriptReturnsBadGatewayOnFailure() throws Exception {
    when(assistantService.getTranscript("google_meet", "abc-defg-hij"))
        .thenThrow(new RuntimeException("provider down"));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail").value("Failed to fetch transcript: provider down"));
  }

  @Test
  void addBotReturnsBadRequestOnBlankMeetingUrl() throws Exception {
    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"\"}"))
        .andExpect(status().isBadRequest());
  }
}
