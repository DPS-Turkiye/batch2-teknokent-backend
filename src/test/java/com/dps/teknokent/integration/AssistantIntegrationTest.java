package com.dps.teknokent.integration;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.dto.responses.MeetingReportActionItem;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.service.MeetingProviderClient;
import com.dps.teknokent.service.MeetingReportService;

import static org.mockito.ArgumentMatchers.anyList;
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

    @MockitoBean
    private MeetingReportService meetingReportService;

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
  void addBotEndToEndReturnsBadGatewayWhenProviderFails() throws Exception {
    when(meetingProviderClient.sendBot(
            eq("google_meet"),
            eq("abc-defg-hij"),
            anyString()))
        .thenThrow(new RuntimeException("provider unavailable"));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail")
            .value("Failed to reach meeting provider: provider unavailable"));
  }

  @Test
  void addBotEndToEndReturnsProviderUnauthorizedWhenApiKeyIsWrong() throws Exception {
    when(meetingProviderClient.sendBot(
            eq("google_meet"),
            eq("abc-defg-hij"),
            anyString()))
        .thenThrow(HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            HttpHeaders.EMPTY,
            "invalid api key".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("invalid api key"));
  }

  @Test
  void addBotEndToEndReturnsBadGatewayWhenConnectionDrops() throws Exception {
    when(meetingProviderClient.sendBot(
            eq("google_meet"),
            eq("abc-defg-hij"),
            anyString()))
        .thenThrow(new ResourceAccessException("I/O error on POST request"));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail")
            .value("Failed to reach meeting provider: I/O error on POST request"));
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

  @Test
  void getTranscriptEndToEndReturnsProviderUnauthorizedWhenApiKeyIsWrong() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenThrow(HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            HttpHeaders.EMPTY,
            "invalid api key".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("invalid api key"));
  }

  @Test
  void getTranscriptEndToEndReturnsBadGatewayWhenConnectionDrops() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenThrow(new ResourceAccessException("I/O error on GET request"));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail").value("Failed to fetch transcript: I/O error on GET request"));
  }

  @Test
  void generateMeetingReportEndToEndReturnsOk() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));
    when(meetingReportService.generate(anyList(), eq(null)))
        .thenReturn(new MeetingReportResponse(
            "Mentorluk Gorusmesi",
            "2026-08-27",
            "Urun Yol Haritasi",
            "Toplanti verimli gecti.",
            List.of("Yol haritasi"),
            List.of("MVP iki haftada cikacak"),
            List.of(new MeetingReportActionItem("Ayşe", "Demo hazirla", "2026-09-01")),
            List.of("Kaynak kisiti"),
            List.of("Backlog netlestir"),
            "gpt-5.4"));

    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Mentorluk Gorusmesi"))
        .andExpect(jsonPath("$.actionItems[0].task").value("Demo hazirla"))
        .andExpect(jsonPath("$.model").value("gpt-5.4"));
  }

  @Test
  void generateMeetingReportEndToEndReturnsBadRequestWhenReportServiceRejectsInput() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));
    when(meetingReportService.generate(anyList(), eq(null)))
        .thenThrow(new InvalidRequestException("Transcript is empty; cannot generate a meeting report."));

    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Transcript is empty; cannot generate a meeting report."));
  }

  @Test
  void generateMeetingReportEndToEndReturnsBadGatewayWhenReportGenerationFails() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));
    when(meetingReportService.generate(anyList(), eq(null)))
        .thenThrow(new RuntimeException("ai provider unavailable"));

    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail").value("Failed to generate meeting report: ai provider unavailable"));
  }
}
