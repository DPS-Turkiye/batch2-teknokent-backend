package com.dps.teknokent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.ConsentAnswer;
import com.dps.teknokent.domain.MentorMatchingMethod;
import com.dps.teknokent.domain.ProfileAnalysisStatus;
import com.dps.teknokent.domain.ProfileType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ConfirmProfileAnalysisRequest;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.dto.responses.MentorRecommendationResult;
import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.exception.AiFeatureDisabledException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.ProfileAnalysisService;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ProfileAnalysisController.class)
@Import(SecurityConfig.class)
class ProfileAnalysisControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProfileAnalysisService profileAnalysisService;

	private static final ConsentAnswer CONSENT = new ConsentAnswer(true, "cv-parsing-2026-11-13");

	@Test
	void requiresAuthenticationForLatest() throws Exception {
		mockMvc.perform(get("/api/profile-analyses/latest"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void returnsLatestProfileAnalysis() throws Exception {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000201");

		when(profileAnalysisService.findLatest(organizationId, null)).thenReturn(new ProfileAnalysisResponse(
				analysisId,
				organizationId,
				null,
				"pitch.pdf",
				ProfileType.MENTEE,
				"{\"startup_name\":\"Acme Labs\"}",
				ProfileAnalysisStatus.DRAFT,
				Instant.parse("2026-08-20T09:00:00Z"),
				"Redacted legal data",
				"OCR summary",
				Instant.parse("2026-08-20T08:55:00Z")));

		mockMvc.perform(get("/api/profile-analyses/latest")
				.param("organizationId", organizationId.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(analysisId.toString()))
				.andExpect(jsonPath("$.sourceFilename").value("pitch.pdf"))
				.andExpect(jsonPath("$.profileType").value("mentee"))
				.andExpect(jsonPath("$.status").value("DRAFT"));
	}

	@Test
	void returnsLatestConfirmedStartup() throws Exception {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000202");

		when(profileAnalysisService.findLatestConfirmedStartup(organizationId))
				.thenReturn(new ProfileAnalysisResponse(
						analysisId,
						organizationId,
						null,
						"confirmed.pdf",
						ProfileType.MENTEE,
						"{\"startup_name\":\"Confirmed Startup\"}",
						ProfileAnalysisStatus.CONFIRMED,
						Instant.parse("2026-08-20T09:10:00Z"),
						"No sensitive fields",
						"OCR summary",
						Instant.parse("2026-08-20T09:05:00Z")));

		mockMvc.perform(get("/api/profile-analyses/latest/confirmed")
				.param("organizationId", organizationId.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(analysisId.toString()))
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void aSwitchedOffParseIsRefusedWithATypedCodeNotAServerError() throws Exception {
		when(profileAnalysisService.parsePreview(eq(ProfileType.INDIVIDUAL_MENTEE), eq(CONSENT), eq("auto"), any()))
				.thenThrow(new AiFeatureDisabledException(AiFeature.PROFILE_PARSING));

		mockMvc.perform(multipart("/api/profile-analyses/preview")
				.file(new MockMultipartFile(
						"file",
						"cv.pdf",
						"application/pdf",
						"dummy pdf bytes".getBytes(StandardCharsets.UTF_8)))
				.param("consentAccepted", "true")
				.param("consentTextVersion", "cv-parsing-2026-11-13")
				.with(user("test-user")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AI_FEATURE_DISABLED"))
				.andExpect(jsonPath("$.message").value("Bu yapay zekâ özelliği bu sistemde kapalı."));
	}

	@Test
	void anOrdinaryErrorCarriesNoCode() throws Exception {
		when(profileAnalysisService.parsePreview(eq(ProfileType.INDIVIDUAL_MENTEE), eq(new ConsentAnswer(false, null)),
				eq("auto"), any()))
				.thenThrow(new InvalidRequestException("Consent required."));

		mockMvc.perform(multipart("/api/profile-analyses/preview")
				.file(new MockMultipartFile(
						"file",
						"cv.pdf",
						"application/pdf",
						"dummy pdf bytes".getBytes(StandardCharsets.UTF_8)))
				.with(user("test-user")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").doesNotExist());
	}

	@Test
	void parsesProfileAnalysisFromPdf() throws Exception {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000103");
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000203");

		when(profileAnalysisService.parse(
				eq(organizationId),
				eq((UUID) null),
				eq(ProfileType.MENTEE),
				eq(CONSENT),
				eq("auto"),
				any())).thenReturn(new ProfileAnalysisResponse(
						analysisId,
						organizationId,
						null,
						"sample.pdf",
						ProfileType.MENTEE,
						"{\"startup_name\":\"Sample Startup\"}",
						ProfileAnalysisStatus.DRAFT,
						Instant.parse("2026-08-20T10:00:00Z"),
						"Summary",
						"OCR summary",
						Instant.parse("2026-08-20T09:58:00Z")));

		mockMvc.perform(multipart("/api/profile-analyses")
				.file(new MockMultipartFile(
						"file",
						"sample.pdf",
						"application/pdf",
						"dummy pdf bytes".getBytes(StandardCharsets.UTF_8)))
				.param("organizationId", organizationId.toString())
				.param("profileType", "mentee")
				.param("consentAccepted", "true")
				.param("consentTextVersion", "cv-parsing-2026-11-13")
				.param("ocrMode", "auto")
				.with(user("test-user")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(analysisId.toString()))
				.andExpect(jsonPath("$.sourceFilename").value("sample.pdf"));
	}

	@Test
	void createsManualProfileAnalysis() throws Exception {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000104");
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000204");

		CreateManualProfileRequest request = new CreateManualProfileRequest(
				ProfileType.INDIVIDUAL_MENTEE,
				new ObjectMapper().readTree("""
						{
						  "startup_name": "Manual Startup",
						  "startup_summary": "We build fintech tools for creators.",
						  "professional_summary": "We are a product-focused team.",
						  "current_role": "Founder",
						  "desired_role": "Product strategy mentor"
						}
						"""));

		when(profileAnalysisService.createManual(eq(organizationId), eq((UUID) null),
				any(CreateManualProfileRequest.class)))
				.thenReturn(new ProfileAnalysisResponse(
						analysisId,
						organizationId,
						null,
						"manual-entry",
						ProfileType.INDIVIDUAL_MENTEE,
						"{\"startup_name\":\"Manual Startup\"}",
						ProfileAnalysisStatus.CONFIRMED,
						Instant.parse("2026-08-20T11:00:00Z"),
						"Manual profile created",
						"No OCR",
						Instant.parse("2026-08-20T10:58:00Z")));

		ProfileAnalysisResponse response = new ProfileAnalysisController(profileAnalysisService)
				.createManual(organizationId, null, request);

		assertThat(response.id()).isEqualTo(analysisId);
		assertThat(response.profileType()).isEqualTo(ProfileType.INDIVIDUAL_MENTEE);
	}

	@Test
	void requiresAuthenticationToParseAStoredCv() throws Exception {
		mockMvc.perform(post("/api/profile-analyses/preview/documents/{id}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void parsesAStoredCvForTheCaller() throws Exception {
		UUID documentId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		when(profileAnalysisService.parseStoredCvPreview(
				any(), eq(documentId), eq(ProfileType.INDIVIDUAL_MENTEE), eq(CONSENT), eq("auto")))
				.thenReturn(java.util.Map.of("current_role", "Ürün Yöneticisi"));

		mockMvc.perform(post("/api/profile-analyses/preview/documents/{id}", documentId)
				.param("consentAccepted", "true")
				.param("consentTextVersion", "cv-parsing-2026-11-13")
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.current_role").value("Ürün Yöneticisi"));
	}

	@Test
	void recommendationsComeBackTypedWithTheirMethodAndAnIsoTimestamp() throws Exception {
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000205");
		UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000007");
		when(profileAnalysisService.recommendations(actorId, analysisId)).thenReturn(new MentorRecommendationResult(
				MentorMatchingMethod.PGVECTOR_SEMANTIC, "openai:text-embedding-3-small",
				Instant.parse("2026-11-14T10:15:30Z"), List.of()));

		mockMvc.perform(post("/api/profile-analyses/{id}/recommendations", analysisId)
				.param("actorId", actorId.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.matchingMethod").value("PGVECTOR_SEMANTIC"))
				.andExpect(jsonPath("$.matchingModel").value("openai:text-embedding-3-small"))
				.andExpect(jsonPath("$.generatedAt").value("2026-11-14T10:15:30Z"))
				.andExpect(jsonPath("$.mentors").isArray());
	}

	@Test
	void theProxyMustNameWhoAsksForRecommendationsOrConfirms() throws Exception {
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000207");

		mockMvc.perform(post("/api/profile-analyses/{id}/recommendations", analysisId)
				.with(user("test-user")))
				.andExpect(status().isBadRequest());
		mockMvc.perform(patch("/api/profile-analyses/{id}/confirmation", analysisId)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"profile\":{\"startup_name\":\"Acme\"}}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(profileAnalysisService);
	}

	@Test
	void aSignedInCallerActsAsThemselvesWhateverActorIdIsSent() throws Exception {
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000208");
		UUID signedIn = UUID.fromString("00000000-0000-0000-0000-000000000003");
		when(profileAnalysisService.recommendations(signedIn, analysisId)).thenReturn(new MentorRecommendationResult(
				MentorMatchingMethod.DETERMINISTIC, "keyword-overlap-v1", Instant.parse("2026-11-14T10:15:30Z"),
				List.of()));

		mockMvc.perform(post("/api/profile-analyses/{id}/recommendations", analysisId)
				.param("actorId", "00000000-0000-0000-0000-000000000999")
				.with(authentication(signedInAs(signedIn))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.matchingMethod").value("DETERMINISTIC"));
		verify(profileAnalysisService).recommendations(signedIn, analysisId);
	}

	@Test
	void anotherStartupsAnalysisIsForbidden() throws Exception {
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000209");
		UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000008");
		when(profileAnalysisService.confirm(eq(actorId), eq(analysisId), any(ConfirmProfileAnalysisRequest.class)))
				.thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu profil analizi sizin değil."));

		mockMvc.perform(patch("/api/profile-analyses/{id}/confirmation", analysisId)
				.param("actorId", actorId.toString())
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"profile\":{\"startup_name\":\"Acme\"}}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void confirmsProfileAnalysis() throws Exception {
		UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000206");
		UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000009");
		ArgumentCaptor<ConfirmProfileAnalysisRequest> sent = ArgumentCaptor
				.forClass(ConfirmProfileAnalysisRequest.class);

		when(profileAnalysisService.confirm(eq(actorId), eq(analysisId), sent.capture()))
				.thenReturn(new ProfileAnalysisResponse(
						analysisId,
						UUID.fromString("00000000-0000-0000-0000-000000000103"),
						null,
						"confirmed.pdf",
						ProfileType.MENTEE,
						"{\"startup_name\":\"Confirmed Startup\"}",
						ProfileAnalysisStatus.CONFIRMED,
						Instant.parse("2026-08-20T12:00:00Z"),
						"Confirmed by user",
						"OCR summary",
						Instant.parse("2026-08-20T11:55:00Z")));

		mockMvc.perform(patch("/api/profile-analyses/{id}/confirmation", analysisId)
				.param("actorId", actorId.toString())
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "profile": {
						    "startup_name": "Confirmed Startup",
						    "startup_summary": "We are building a B2B SaaS analytics platform."
						  }
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(analysisId.toString()))
				.andExpect(jsonPath("$.profileType").value("mentee"))
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
		assertThat(sent.getValue().profile().path("startup_name").asString()).isEqualTo("Confirmed Startup");
	}

	@Test
	void aConfirmationWithoutAProfileIsRefusedBeforeTheService() throws Exception {
		mockMvc.perform(patch("/api/profile-analyses/{id}/confirmation", UUID.randomUUID())
				.param("actorId", UUID.randomUUID().toString())
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"startup_name\":\"Acme\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(profileAnalysisService);
	}

	@Test
	void aLowercaseProfileTypeParameterIsReadAsItsType() throws Exception {
		when(profileAnalysisService.parseStoredCvPreview(any(), any(), eq(ProfileType.MENTOR), eq(CONSENT), eq("auto")))
				.thenReturn(java.util.Map.of("current_company", "Looply"));

		mockMvc.perform(post("/api/profile-analyses/preview/documents/{id}", UUID.randomUUID())
				.param("profileType", "mentor")
				.param("consentAccepted", "true")
				.param("consentTextVersion", "cv-parsing-2026-11-13")
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.current_company").value("Looply"));
	}

	@Test
	void anUnknownProfileTypeIsABadRequestWithTheUsualErrorBody() throws Exception {
		mockMvc.perform(post("/api/profile-analyses/preview/documents/{id}", UUID.randomUUID())
				.param("profileType", "investor")
				.param("consentAccepted", "true")
				.with(user("test-user")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Profile type must be mentee, individual_mentee or mentor."));
		verifyNoInteractions(profileAnalysisService);
	}

	@Test
	void aManualProfileOfAnUnknownTypeIsABadRequest() throws Exception {
		mockMvc.perform(post("/api/profile-analyses/manual")
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"profileType\":\"investor\",\"profile\":{\"startup_name\":\"Acme\"}}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(profileAnalysisService);
	}

	private static UsernamePasswordAuthenticationToken signedInAs(UUID userId) {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(userId, "founder@dongu.co", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}
}
