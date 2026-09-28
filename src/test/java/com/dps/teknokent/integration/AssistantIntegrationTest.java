package com.dps.teknokent.integration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AssistantReportGeneration;
import com.dps.teknokent.entity.AssistantSession;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.AssistantReportGenerationRepository;
import com.dps.teknokent.repository.AssistantSessionRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.MeetingProviderClient;
import com.dps.teknokent.service.MeetingReportService;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rate-limiting.enabled=true")
class AssistantIntegrationTest {

  private static final String MEET_URL = "https://meet.google.com/abc-defg-hij";
  private static final String MEET_CODE = "abc-defg-hij";

  @Autowired private MockMvc mockMvc;
  @Autowired private AppUserRepository appUserRepository;
  @Autowired private AssistantSessionRepository sessionRepository;
  @Autowired private AssistantReportGenerationRepository reportGenerationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private MeetingProviderClient meetingProviderClient;
  @MockitoBean private MeetingReportService meetingReportService;
  @MockitoBean private StringRedisTemplate redisTemplate;

  private AppUser owner;

  @BeforeEach
  void createOwner() {
    jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS assistant_session_lock (lock_key VARCHAR(300) PRIMARY KEY)");
    owner = user("owner");
  }

  @AfterEach
  void removeOwnersAndSessions() {
    reportGenerationRepository.deleteAll();
    sessionRepository.deleteAll();
    appUserRepository.deleteAll();
  }

  @Test
  void rejectsAnonymousSessionCreationBeforeCallingProvider() throws Exception {
    mockMvc.perform(post("/api/assistant/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"" + MEET_URL + "\"}"))
        .andExpect(status().isUnauthorized());
    verify(meetingProviderClient, never()).sendBot(anyString(), anyString(), anyString());
  }

  @Test
  void createsOpaqueSessionAndReusesItForOwner() throws Exception {
    when(meetingProviderClient.sendBot(eq("google_meet"), eq(MEET_CODE), eq(MEET_URL)))
        .thenReturn(new ProviderBotResponse("provider-bot-1", "joining", "Mentor Bot"));

    var first = mockMvc.perform(post("/api/assistant/sessions").with(asUser(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"" + MEET_URL + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.assistantSessionId").isNotEmpty())
        .andExpect(jsonPath("$.nativeMeetingId").doesNotExist())
        .andReturn();

    String sessionId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.assistantSessionId");
    UUID.fromString(sessionId);
    mockMvc.perform(post("/api/assistant/sessions").with(asUser(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"" + MEET_URL + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.assistantSessionId").value(sessionId));
    org.mockito.Mockito.verify(meetingProviderClient).sendBot("google_meet", MEET_CODE, MEET_URL);
  }

  @Test
  void rejectsOtherUsersEvenWhenTheyKnowTheSessionId() throws Exception {
    AssistantSession session = existingSession();
    AppUser stranger = user("stranger");

    mockMvc.perform(get("/api/assistant/sessions/{id}/transcript", session.getId())
            .with(asUser(stranger)))
        .andExpect(status().isForbidden());
    verify(meetingProviderClient, never()).getTranscript(anyString(), anyString());
  }

  @Test
  void readsTranscriptOnlyThroughOwnedSession() throws Exception {
    AssistantSession session = existingSession();
    when(meetingProviderClient.getTranscript("google_meet", MEET_CODE))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0))));

    mockMvc.perform(get("/api/assistant/sessions/{id}/transcript", session.getId())
            .with(asUser(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.segments[0].text").value("Merhaba"));
  }

  @Test
  void rejectsLookalikeMeetHost() throws Exception {
    mockMvc.perform(post("/api/assistant/sessions").with(asUser(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com.evil.example/abc-defg-hij\"}"))
        .andExpect(status().isBadRequest());
    verify(meetingProviderClient, never()).sendBot(anyString(), anyString(), anyString());
  }

  @Test
  void enforcesTwentyReportsPerActorPerDay() throws Exception {
    AssistantSession session = existingSession();
    for (int i = 0; i < 20; i++) {
      reportGenerationRepository.save(new AssistantReportGeneration(owner));
    }
    reportGenerationRepository.flush();

    mockMvc.perform(post("/api/assistant/sessions/{id}/report", session.getId())
            .with(asUser(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isTooManyRequests());
    verify(meetingReportService, never()).generate(org.mockito.ArgumentMatchers.anyList(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void stillAppliesTheRequestRateLimitToAuthenticatedSessions() throws Exception {
    existingSession();
    String ip = "198.51.100.42";
    for (int i = 0; i < 100; i++) {
      mockMvc.perform(post("/api/assistant/sessions").with(asUser(owner))
              .header("X-Forwarded-For", ip)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"meetingUrl\":\"" + MEET_URL + "\"}"))
          .andExpect(status().isCreated());
    }
    mockMvc.perform(post("/api/assistant/sessions").with(asUser(owner))
            .header("X-Forwarded-For", ip)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"" + MEET_URL + "\"}"))
        .andExpect(status().isTooManyRequests());
  }

  private AssistantSession existingSession() {
    AssistantSession session = new AssistantSession();
    session.setPlatform("google_meet");
    session.setNativeMeetingId(MEET_CODE);
    session.setOwner(owner);
    return sessionRepository.saveAndFlush(session);
  }

  private AppUser user(String prefix) {
    AppUser user = new AppUser();
    user.setEmail(prefix + "+" + UUID.randomUUID() + "@example.test");
    user.setFullName(prefix);
    user.setRole(UserRole.MENTOR);
    user.setStatus(UserAccountStatus.APPROVED);
    user.setFirebaseUid("firebase-" + UUID.randomUUID());
    return appUserRepository.saveAndFlush(user);
  }

  private RequestPostProcessor asUser(AppUser user) {
    var principal = new AppUserPrincipal(user.getId(), user.getEmail(), user.getRole(),
        user.getStatus(), user.getFirebaseUid());
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        principal, "firebase", List.of(new SimpleGrantedAuthority("ROLE_MENTOR")));
    return authentication(authentication);
  }
}
