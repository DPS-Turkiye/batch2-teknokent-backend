package com.dps.teknokent.integration;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.service.MeetingProviderClient;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AssistantIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MeetingProviderClient meetingProviderClient;

  @Test
  void addBotEndToEndReturnsCreated() throws Exception {
    when(meetingProviderClient.sendBot(
            eq("google_meet"),
            eq("abc-defg-hij"),
            anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("bot-1"))
        .andExpect(jsonPath("$.platform").value("google_meet"))
        .andExpect(jsonPath("$.nativeMeetingId").value("abc-defg-hij"));
  }

  @Test
  void addBotEndToEndReturnsBadRequestForUnsupportedUrl() throws Exception {
    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://zoom.us/j/1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void getTranscriptEndToEndReturnsSegments() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.segments[0].speaker").value("Ayşe"))
        .andExpect(jsonPath("$.segments[0].text").value("Merhaba"))
        .andExpect(jsonPath("$.segments[0].at").value("01:05"));
  }
}
