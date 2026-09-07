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
import org.springframework.data.redis.core.StringRedisTemplate;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

  @MockitoBean
  private StringRedisTemplate redisTemplate;

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
    void addBotEndToEndRejectsBlankMeetingUrl() throws Exception {
        mockMvc.perform(post("/api/assistant/bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingUrl\":\"   \"}"))
                .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Request validation failed"))
          .andExpect(jsonPath("$.fieldErrors.meetingUrl").value("must not be blank"));
    }

    @Test
    void addBotEndToEndRejectsNullMeetingUrl() throws Exception {
        mockMvc.perform(post("/api/assistant/bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingUrl\":null}"))
                .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Request validation failed"))
          .andExpect(jsonPath("$.fieldErrors.meetingUrl").value("must not be blank"));
    }

    @Test
    void addBotEndToEndRejectsUnexpectedMeetingUrlType() throws Exception {
        mockMvc.perform(post("/api/assistant/bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingUrl\":{\"value\":\"https://meet.google.com/abc-defg-hij\"}}"))
                .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Request body is malformed or contains an unsupported value"));
    }

    @Test
    void addBotEndToEndRejectsSqlInjectionLikeInputWithoutLeakingSqlDetails() throws Exception {
        mockMvc.perform(post("/api/assistant/bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingUrl\":\"https://zoom.us/j/%27OR%201%3D1--\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("syntax error"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test
    void addBotEndToEndRejectsDoubleQuoteSqlLikeInputWithoutLeakingSqlDetails() throws Exception {
        mockMvc.perform(post("/api/assistant/bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingUrl\":\"https://zoom.us/j/%22OR%20%22a%22=%22a%22--\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("syntax error"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test
    void getTranscriptRejectsSqlInjectionLikePathWithoutLeakingSqlDetails() throws Exception {
        when(meetingProviderClient.getTranscript(anyString(), anyString()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        HttpHeaders.EMPTY,
                        "invalid native meeting id".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/assistant/transcripts/google_meet/%27OR%201%3D1--"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("syntax error"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

            @Test
            void getTranscriptUsesPathVariableWhenQueryParameterIsTampered() throws Exception {
            when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
                .thenReturn(new ProviderTranscriptResponse(List.of()));

            mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij?nativeMeetingId=manipulated-id"))
                .andExpect(status().isOk());

            verify(meetingProviderClient).getTranscript("google_meet", "abc-defg-hij");
            }

            @Test
            void getTranscriptRejectsTraversalLikeMeetingIdWithoutLeakingSqlDetails() throws Exception {
            when(meetingProviderClient.getTranscript(anyString(), anyString()))
                .thenThrow(HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    HttpHeaders.EMPTY,
                    "invalid native meeting id".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));

            mockMvc.perform(get("/api/assistant/transcripts/google_meet/%2e%2e%2fadmin"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("syntax error"))))
                .andExpect(content().string(not(containsString("jdbc"))));
            }

    @Test
    void stopBotRejectsSqlInjectionLikePathWithoutLeakingSqlDetails() throws Exception {
        doThrow(HttpClientErrorException.create(
            HttpStatus.BAD_REQUEST,
            "Bad Request",
            HttpHeaders.EMPTY,
            "invalid native meeting id".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8))
                .when(meetingProviderClient).stopBot(anyString(), anyString());

        mockMvc.perform(delete("/api/assistant/bots/google_meet/%27OR%201%3D1--"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("syntax error"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test
    void generateMeetingReportRejectsSqlInjectionLikePathWithoutLeakingSqlDetails() throws Exception {
        when(meetingProviderClient.getTranscript(anyString(), anyString()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        HttpHeaders.EMPTY,
                        "invalid native meeting id".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/assistant/bots/google_meet/%27OR%201%3D1--/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("syntax error"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test
    void generateMeetingReportRejectsMalformedPayload() throws Exception {
        mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is malformed or contains an unsupported value"));
    }

    @Test
    void protectedEndpointRejectsUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/api/organizations/11111111-1111-1111-1111-111111111111/bottlenecks"))
                .andExpect(status().isUnauthorized());
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

  @Test
  void rateLimitingIsAppliedToAssistantEndpoints() throws Exception {
    when(meetingProviderClient.sendBot(eq("google_meet"), eq("abc-defg-hij"), anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    // Simulate exhausting rate limit by making 101 requests (limit is 100/minute)
    for (int i = 0; i < 100; i++) {
      mockMvc.perform(post("/api/assistant/bots")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
              .header("X-Forwarded-For", "192.168.1.1"))
          .andExpect(status().isCreated());
    }

    // 101st request should be rate limited
    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
            .header("X-Forwarded-For", "192.168.1.1"))
        .andExpect(status().isTooManyRequests())
        .andExpect(result -> {
          String retryAfter = result.getResponse().getHeader("X-Rate-Limit-Retry-After-Seconds");
          assert retryAfter != null;
          assert Integer.parseInt(retryAfter) > 0;
        });
  }

  @Test
  void differentIpsHaveSeparateRateLimits() throws Exception {
    when(meetingProviderClient.sendBot(eq("google_meet"), eq("abc-defg-hij"), anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    // IP 1 - single request should succeed
    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
            .header("X-Forwarded-For", "10.0.0.1"))
        .andExpect(status().isCreated());

    // IP 2 - single request should also succeed (different bucket)
    // Because they're from different IPs, they should have separate rate limit buckets
    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
            .header("X-Forwarded-For", "10.0.0.2"))
        .andExpect(status().isCreated());
  }

  @Test
  void rateLimitHeaderIsIncluded() throws Exception {
    when(meetingProviderClient.sendBot(eq("google_meet"), eq("abc-defg-hij"), anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
            .header("X-Forwarded-For", "10.0.0.100"))
        .andExpect(status().isCreated())
        .andExpect(result -> {
          String remaining = result.getResponse().getHeader("X-Rate-Limit-Remaining");
          assert remaining != null;
          assert Integer.parseInt(remaining) == 99;
        });
  }
}
