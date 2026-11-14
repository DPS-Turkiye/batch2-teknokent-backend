package com.dps.teknokent.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.MeetingBotEndReason;
import com.dps.teknokent.domain.MeetingBotState;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.BotStateResponse;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.exception.AiFeatureDisabledException;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.MeetingProviderException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AssistantService;

@WebMvcTest(AssistantController.class)
@Import(SecurityConfig.class)
class AssistantControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AssistantService assistantService;

	private static final UUID CALLER = UUID.randomUUID();

	private static org.springframework.test.web.servlet.request.RequestPostProcessor signedIn() {
		return authentication(new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				CALLER, "caller@test.dev", UserRole.MENTEE, UserAccountStatus.APPROVED, "caller-uid"), null,
				List.of()));
	}

	@Test
	void addBotReturnsCreated() throws Exception {
		when(assistantService.addAdHocBot(eq(CALLER), any(AddBotRequest.class)))
				.thenReturn(new AddBotResponse(
						"bot-1",
						"google_meet",
						"abc-defg-hij",
						"joining",
						"Mentor Bot"));

		mockMvc.perform(post("/api/assistant/bots").with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value("bot-1"))
				.andExpect(jsonPath("$.platform").value("google_meet"))
				.andExpect(jsonPath("$.nativeMeetingId").value("abc-defg-hij"));
	}

	@Test
	void addBotReturnsBadRequestOnInvalidUrl() throws Exception {
		when(assistantService.addAdHocBot(eq(CALLER), any(AddBotRequest.class)))
				.thenThrow(new InvalidRequestException("Bu toplantı bağlantısı desteklenmiyor."));

		mockMvc.perform(post("/api/assistant/bots").with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"meetingUrl\":\"https://zoom.us/j/1\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Bu toplantı bağlantısı desteklenmiyor."));
	}

	@Test
	void addBotReturnsConflictWithItsCodeWhenEveryRecordingSlotIsTaken() throws Exception {
		when(assistantService.addAdHocBot(eq(CALLER), any(AddBotRequest.class)))
				.thenThrow(new ConflictException("Şu anda tüm kayıt yerleri dolu.",
						AssistantService.RECORDING_SLOTS_FULL));

		mockMvc.perform(post("/api/assistant/bots").with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RECORDING_SLOTS_FULL"))
				.andExpect(jsonPath("$.message").value("Şu anda tüm kayıt yerleri dolu."));
	}

	@Test
	void aProviderFailureIsABadGatewayThatKeepsTheProvidersAnswerOutOfTheBody() throws Exception {
		when(assistantService.addAdHocBot(eq(CALLER), any(AddBotRequest.class)))
				.thenThrow(new MeetingProviderException("Could not send the bot", HttpClientErrorException.create(
						HttpStatus.UNAUTHORIZED,
						"Unauthorized",
						HttpHeaders.EMPTY,
						"invalid api key at vexa.internal".getBytes(StandardCharsets.UTF_8),
						StandardCharsets.UTF_8)));

		mockMvc.perform(post("/api/assistant/bots").with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("MEETING_PROVIDER_UNAVAILABLE"))
				.andExpect(jsonPath("$.message")
						.value("Toplantı asistanına şu anda ulaşılamıyor. Biraz sonra tekrar dene."))
				.andExpect(content().string(not(containsString("vexa.internal"))));
	}

	@Test
	void getTranscriptReturnsOk() throws Exception {
		when(assistantService.getTranscript(CALLER, "google_meet", "abc-defg-hij"))
				.thenReturn(new TranscriptResponse(List.of(
						new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "01:05"))));

		mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.segments[0].speaker").value("Ayşe"))
				.andExpect(jsonPath("$.segments[0].text").value("Merhaba"))
				.andExpect(jsonPath("$.segments[0].at").value("01:05"));
	}

	@Test
	void getTranscriptReturnsBadGatewayOnFailure() throws Exception {
		when(assistantService.getTranscript(CALLER, "google_meet", "abc-defg-hij"))
				.thenThrow(new MeetingProviderException("Could not fetch the transcript"));

		mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("MEETING_PROVIDER_UNAVAILABLE"));
	}

	@Test
	void addBotReturnsBadRequestOnBlankMeetingUrl() throws Exception {
		mockMvc.perform(post("/api/assistant/bots").with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"meetingUrl\":\"\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void stopBotReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/assistant/bots/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isNoContent());
	}

	@Test
	void stopBotReturnsBadGatewayWhenTheProviderFails() throws Exception {
		doThrow(new MeetingProviderException("Could not stop the bot"))
				.when(assistantService).stopBot(CALLER, "google_meet", "abc-defg-hij");

		mockMvc.perform(delete("/api/assistant/bots/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("MEETING_PROVIDER_UNAVAILABLE"));
	}

	@Test
	void aConflictOrABadRequestKeepsItsOwnStatusInsteadOfBecomingABadGateway() throws Exception {
		doThrow(new ConflictException("Kayıt botu şu anda başka bir görüşmede."))
				.when(assistantService).stopBot(CALLER, "google_meet", "abc-defg-hij");
		when(assistantService.getTranscript(CALLER, "google_meet", "abc-defg-hij"))
				.thenThrow(new InvalidRequestException("Geçersiz istek."));

		mockMvc.perform(delete("/api/assistant/bots/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Kayıt botu şu anda başka bir görüşmede."));
		mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Geçersiz istek."));
	}

	@Test
	void stopBotActsForTheSignedInCallerOnly() throws Exception {
		mockMvc.perform(delete("/api/assistant/bots/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isNoContent());

		verify(assistantService).stopBot(CALLER, "google_meet", "abc-defg-hij");
	}

	@Test
	void theSharedServiceCredentialsNameNobody() throws Exception {
		when(assistantService.getTranscript(isNull(), eq("google_meet"), eq("abc-defg-hij")))
				.thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "A signed-in user is required."));

		mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij")
				.param("actorId", CALLER.toString())
				.with(user("test-user")))
				.andExpect(status().isForbidden());
	}

	@Test
	void aNonParticipantGetsForbiddenNotAProviderError() throws Exception {
		var forbidden = new ResponseStatusException(HttpStatus.FORBIDDEN,
				"Only a participant of this meeting can use its assistant.");
		when(assistantService.getTranscript(CALLER, "google_meet", "abc-defg-hij")).thenThrow(forbidden);
		when(assistantService.addAdHocBot(eq(CALLER), any(AddBotRequest.class))).thenThrow(forbidden);
		when(assistantService.generateMeetingReport(CALLER, "google_meet", "abc-defg-hij")).thenThrow(forbidden);
		doThrow(forbidden).when(assistantService).stopBot(CALLER, "google_meet", "abc-defg-hij");

		mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/assistant/bots").with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report").with(signedIn()))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete("/api/assistant/bots/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isForbidden());
	}

	@Test
	void getBotStateReturnsTheStateAndEndReason() throws Exception {
		when(assistantService.botState(CALLER, "google_meet", "abc-defg-hij"))
				.thenReturn(new BotStateResponse("google_meet", "abc-defg-hij",
						MeetingBotState.FAILED, MeetingBotEndReason.JOIN_FAILED,
						java.time.Instant.parse("2026-11-11T20:03:06Z")));

		mockMvc.perform(get("/api/assistant/bots/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("FAILED"))
				.andExpect(jsonPath("$.endReason").value("JOIN_FAILED"))
				.andExpect(jsonPath("$.requestedAt").value("2026-11-11T20:03:06Z"));
	}

	@Test
	void getBotStateReturnsBadGatewayWhenTheProviderIsDown() throws Exception {
		when(assistantService.botState(CALLER, "google_meet", "abc-defg-hij"))
				.thenThrow(new MeetingProviderException("Could not read the bot state",
						new IllegalStateException("connection refused")));

		mockMvc.perform(get("/api/assistant/bots/google_meet/abc-defg-hij").with(signedIn()))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("MEETING_PROVIDER_UNAVAILABLE"));
	}

	@Test
	void getBotStateOfAMeetingTheCallerIsNotInIsNotFound() throws Exception {
		when(assistantService.botState(CALLER, "google_meet", "zzz-zzzz-zzz"))
				.thenThrow(new ResourceNotFoundException("Meeting not found."));

		mockMvc.perform(get("/api/assistant/bots/google_meet/zzz-zzzz-zzz").with(signedIn()))
				.andExpect(status().isNotFound());
	}

	@Test
	void anUnknownMeetingIsNotFound() throws Exception {
		when(assistantService.getTranscript(CALLER, "google_meet", "zzz-zzzz-zzz"))
				.thenThrow(new ResourceNotFoundException("Meeting not found."));

		mockMvc.perform(get("/api/assistant/transcripts/google_meet/zzz-zzzz-zzz").with(signedIn()))
				.andExpect(status().isNotFound());
	}

	@Test
	void generateMeetingReportIgnoresAModelAndSegmentsFromTheClient() throws Exception {
		when(assistantService.generateMeetingReport(CALLER, "google_meet", "abc-defg-hij"))
				.thenReturn(new MeetingReportResponse("Rapor", null, null, "Ozet",
						List.of(), List.of(), List.of(), List.of(), List.of(), "configured-model"));

		mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report").with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"model\":\"expensive/model\",\"segments\":[{\"id\":\"x\",\"speaker\":\"A\","
						+ "\"text\":\"any text through our key\",\"at\":\"00:00\"}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.model").value("configured-model"));

		verify(assistantService).generateMeetingReport(CALLER, "google_meet", "abc-defg-hij");
	}

	@Test
	void aSwitchedOffMeetingReportIsRefusedWithATypedCodeNotAProviderFailure() throws Exception {
		when(assistantService.generateMeetingReport(CALLER, "google_meet", "abc-defg-hij"))
				.thenThrow(new AiFeatureDisabledException(AiFeature.MEETING_REPORT));

		mockMvc.perform(post("/api/assistant/bots/google_meet/abc-defg-hij/report").with(signedIn()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AI_FEATURE_DISABLED"));
	}
}
