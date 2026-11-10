package com.dps.teknokent.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderBotStatusResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.dto.responses.MeetingReportActionItem;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.service.MeetingProviderClient;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.MeetingReportService;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
@TestPropertySource(properties = "rate-limiting.enabled=true")
@Transactional
class AssistantIntegrationTest {

  private static final String MEET_LINK = "https://meet.google.com/abc-defg-hij";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MeetingProviderClient meetingProviderClient;

  @MockitoBean
  private MeetingReportService meetingReportService;

  @MockitoBean
  private StringRedisTemplate redisTemplate;

  @Autowired
  private AppUserRepository userRepository;

  @Autowired
  private OrganizationRepository organizationRepository;

  @Autowired
  private MeetingRepository meetingRepository;

  private Organization team;
  private AppUser mentor;
  private AppUser member;
  private AppUser outsider;
  private AppUser admin;

  @BeforeEach
  void noBotsRunning() {
    when(meetingProviderClient.getRunningBots()).thenReturn(new ProviderBotStatusResponse(List.of()));
  }

  @BeforeEach
  void aMeetingOnTheMeetCode() {
    team = organization("Assistant Test Team");
    mentor = user(UserRole.MENTOR, null);
    member = user(UserRole.MENTEE, team);
    outsider = user(UserRole.MENTEE, organization("Another Team"));
    admin = user(UserRole.ADMIN, null);
    meeting(team, mentor, MEET_LINK, MeetingStatus.SCHEDULED);
  }

  @Test
  void addBotEndToEndReturnsCreated() throws Exception {
    when(meetingProviderClient.sendBot(
            eq("google_meet"),
            eq("abc-defg-hij"),
            anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("bot-1"))
        .andExpect(jsonPath("$.platform").value("google_meet"))
        .andExpect(jsonPath("$.nativeMeetingId").value("abc-defg-hij"));
  }

  @Test
  void addBotEndToEndReturnsBadRequestForUnsupportedUrl() throws Exception {
    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://zoom.us/j/1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
  }

    @Test
    void addBotEndToEndRejectsBlankMeetingUrl() throws Exception {
        mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "en")
                        .content("{\"meetingUrl\":\"   \"}"))
                .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Request validation failed"))
          .andExpect(jsonPath("$.fieldErrors.meetingUrl").value("must not be blank"));
    }

    @Test
    void addBotEndToEndRejectsNullMeetingUrl() throws Exception {
        mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingUrl\":null}"))
                .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Request validation failed"))
          .andExpect(jsonPath("$.fieldErrors.meetingUrl").value("boş değer olamaz"));
    }

    @Test
    void addBotEndToEndRejectsUnexpectedMeetingUrlType() throws Exception {
        mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingUrl\":{\"value\":\"https://meet.google.com/abc-defg-hij\"}}"))
                .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Request body is malformed or contains an unsupported value"));
    }

    @Test
    void addBotEndToEndRejectsSqlInjectionLikeInputWithoutLeakingSqlDetails() throws Exception {
        mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
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
        mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
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

        mockMvc.perform(get("/api/assistant/transcripts/google_meet/%27OR%201%3D1--").with(signedIn(member)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("syntax error"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

            @Test
            void getTranscriptUsesPathVariableWhenQueryParameterIsTampered() throws Exception {
            when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
                .thenReturn(new ProviderTranscriptResponse(List.of()));

            mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij?nativeMeetingId=manipulated-id").with(signedIn(member)))
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

            mockMvc.perform(get("/api/assistant/transcripts/google_meet/%2e%2e%2fadmin").with(signedIn(member)))
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

        mockMvc.perform(delete("/api/assistant/bots/google_meet/%27OR%201%3D1--").with(signedIn(member)))
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

        mockMvc.perform(post("/api/assistant/bots/google_meet/%27OR%201%3D1--/report").with(signedIn(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("syntax error"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test
    void protectedEndpointRejectsUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/api/organizations/11111111-1111-1111-1111-111111111111/bottlenecks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assistantEndpointsRejectAnonymousCallers() throws Exception {
        mockMvc.perform(post("/api/assistant/bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/assistant/bots/google_meet/abc-defg-hij"))
                .andExpect(status().isUnauthorized());
    }

  @Test
  void addBotEndToEndReturnsBadGatewayWhenProviderFails() throws Exception {
    when(meetingProviderClient.sendBot(
            eq("google_meet"),
            eq("abc-defg-hij"),
            anyString()))
        .thenThrow(new RuntimeException("provider unavailable"));

    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail")
            .value("Failed to reach meeting provider"));
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

    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Failed to reach meeting provider"));
  }

  @Test
  void addBotEndToEndReturnsBadGatewayWhenConnectionDrops() throws Exception {
    when(meetingProviderClient.sendBot(
            eq("google_meet"),
            eq("abc-defg-hij"),
            anyString()))
        .thenThrow(new ResourceAccessException("I/O error on POST request"));

    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail")
            .value("Failed to reach meeting provider"));
  }

  @Test
  void getTranscriptEndToEndReturnsSegments() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn(member)))
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

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn(member)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Failed to fetch transcript"));
  }

  @Test
  void getTranscriptEndToEndReturnsBadGatewayWhenConnectionDrops() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenThrow(new ResourceAccessException("I/O error on GET request"));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn(member)))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail").value("Failed to fetch transcript"));
  }

  @Test
  void generateMeetingReportEndToEndReturnsOk() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));
    when(meetingReportService.generate(anyList()))
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

    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report").with(signedIn(member))
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
    when(meetingReportService.generate(anyList()))
        .thenThrow(new InvalidRequestException("Transcript is empty; cannot generate a meeting report."));

    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report").with(signedIn(member))
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
    when(meetingReportService.generate(anyList()))
        .thenThrow(new RuntimeException("ai provider unavailable"));

    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail").value("Failed to generate meeting report"));
  }

  @Test
  void rateLimitingIsAppliedToAssistantEndpoints() throws Exception {
    when(meetingProviderClient.sendBot(eq("google_meet"), eq("abc-defg-hij"), anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    // Simulate exhausting rate limit by making 101 requests (limit is 100/minute)
    for (int i = 0; i < 100; i++) {
      mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
              .header("X-Forwarded-For", "192.168.1.1"))
          .andExpect(status().isCreated());
    }

    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
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

  // A caller can write anything at the front of X-Forwarded-For; CloudFront appends the real address.
  @Test
  void rateLimitIgnoresSpoofedForwardedForEntries() throws Exception {
    when(meetingProviderClient.sendBot(eq("google_meet"), eq("abc-defg-hij"), anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    for (int i = 0; i < 100; i++) {
      mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
              .header("X-Forwarded-For", "10.9.0." + i + ", 203.0.113.7"))
          .andExpect(status().isCreated());
    }

    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
            .header("X-Forwarded-For", "10.9.1.1, 203.0.113.7"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void differentIpsHaveSeparateRateLimits() throws Exception {
    when(meetingProviderClient.sendBot(eq("google_meet"), eq("abc-defg-hij"), anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
            .header("X-Forwarded-For", "10.0.0.1"))
        .andExpect(status().isCreated());

    // IP 2 - single request should also succeed (different bucket)
    // Because they're from different IPs, they should have separate rate limit buckets
    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}")
            .header("X-Forwarded-For", "10.0.0.2"))
        .andExpect(status().isCreated());
  }

  @Test
  void rateLimitHeaderIsIncluded() throws Exception {
    when(meetingProviderClient.sendBot(eq("google_meet"), eq("abc-defg-hij"), anyString()))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    mockMvc.perform(post("/api/assistant/bots").with(signedIn(member))
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

  @Test
  void theMeetingsMentorTeamMemberAndAnAdminMayReadTheTranscript() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of()));

    for (AppUser allowed : List.of(mentor, member, admin)) {
      mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn(allowed)))
          .andExpect(status().isOk());
    }
  }

  @Test
  void aGuestTeamWithACopyOfTheMeetingMayUseItsAssistant() throws Exception {
    Organization guestTeam = organization("Guest Team");
    meeting(guestTeam, mentor, MEET_LINK, MeetingStatus.SCHEDULED);
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of()));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij")
            .with(signedIn(user(UserRole.MENTEE, guestTeam))))
        .andExpect(status().isOk());
  }

  @Test
  void aNonParticipantIsRefusedEveryAssistantActionAndTheProviderIsNeverCalled() throws Exception {
    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn(outsider)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/assistant/bots").with(signedIn(outsider))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete("/api/assistant/bots/google_meet/abc-defg-hij").with(signedIn(outsider)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report").with(signedIn(outsider)))
        .andExpect(status().isForbidden());

    verify(meetingProviderClient, never()).getTranscript(anyString(), anyString());
    verify(meetingProviderClient, never()).sendBot(anyString(), anyString(), anyString());
    verify(meetingProviderClient, never()).stopBot(anyString(), anyString());
    verify(meetingReportService, never()).generate(any());
  }

  @Test
  void aNonParticipantCannotStopTheBotOrEndTheMeeting() throws Exception {
    Meeting running = meeting(team, mentor, "https://meet.google.com/run-ning-bot", MeetingStatus.SCHEDULED);

    mockMvc.perform(delete("/api/assistant/bots/google_meet/run-ning-bot").with(signedIn(outsider)))
        .andExpect(status().isForbidden());

    org.assertj.core.api.Assertions.assertThat(meetingRepository.findById(running.getId()).orElseThrow().getStatus())
        .isEqualTo(MeetingStatus.SCHEDULED);
  }

  @Test
  void aCancelledMeetingGivesNoAccess() throws Exception {
    meeting(organization("Cancelled Team"), mentor, "https://meet.google.com/can-celd-mtg", MeetingStatus.CANCELLED);

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/can-celd-mtg").with(signedIn(member)))
        .andExpect(status().isNotFound());
    verify(meetingProviderClient, never()).getTranscript(anyString(), anyString());
  }

  @Test
  void aMeetCodeWithNoMentonMeetingIsNotFoundEvenForAnAdmin() throws Exception {
    mockMvc.perform(get("/api/assistant/transcripts/google_meet/zzz-zzzz-zzz").with(signedIn(admin)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/assistant/bots").with(signedIn(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/zzz-zzzz-zzz\"}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/assistant/transcripts/zoom/abc-defg-hij").with(signedIn(admin)))
        .andExpect(status().isNotFound());

    verify(meetingProviderClient, never()).getTranscript(anyString(), anyString());
    verify(meetingProviderClient, never()).sendBot(anyString(), anyString(), anyString());
  }

  @Test
  void theSharedServiceCredentialsCannotActForAnybody() throws Exception {
    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij")
            .param("actorId", member.getId().toString())
            .with(sharedServiceCredentials()))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report")
            .param("actorId", member.getId().toString())
            .with(sharedServiceCredentials()))
        .andExpect(status().isForbidden());

    verify(meetingProviderClient, never()).getTranscript(anyString(), anyString());
  }

  @Test
  void theReportIgnoresTheClientsModelAndSegments() throws Exception {
    when(meetingProviderClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Gerçek metin", 65.0)
        )));
    when(meetingReportService.generate(anyList()))
        .thenReturn(new MeetingReportResponse("Rapor", null, null, "Ozet",
            List.of(), List.of(), List.of(), List.of(), List.of(), "configured-model"));

    mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report").with(signedIn(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"model\":\"expensive/model\",\"segments\":[{\"id\":\"x\",\"speaker\":\"A\","
                + "\"text\":\"any text through our key\",\"at\":\"00:00\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value("configured-model"));

    verify(meetingReportService).generate(List.of(
        new TranscriptSegmentResponse("s1", "Ayşe", "Gerçek metin", "01:05")));
  }

  private Organization organization(String name) {
    Organization organization = new Organization();
    organization.setName(name);
    return organizationRepository.saveAndFlush(organization);
  }

  private AppUser user(UserRole role, Organization organization) {
    AppUser user = new AppUser();
    user.setFullName("Assistant Test " + role);
    user.setEmail(UUID.randomUUID() + "@assistant.test");
    user.setRole(role);
    user.setOrganization(organization);
    return userRepository.saveAndFlush(user);
  }

  private Meeting meeting(Organization organization, AppUser meetingMentor, String meetLink, MeetingStatus status) {
    Meeting meeting = new Meeting();
    meeting.setOrganization(organization);
    meeting.setMentor(meetingMentor);
    meeting.setScheduledAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    meeting.setDurationMinutes(45);
    meeting.setMeetLink(meetLink);
    meeting.setStatus(status);
    return meetingRepository.saveAndFlush(meeting);
  }

  private static RequestPostProcessor sharedServiceCredentials() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("service");
  }

  private static RequestPostProcessor signedIn(AppUser user) {
    return authentication(new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
        user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "assistant-test-uid"), null, List.of()));
  }
}
