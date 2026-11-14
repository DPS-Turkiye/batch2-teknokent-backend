package com.dps.teknokent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.ConsentAnswer;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.exception.AiFeatureDisabledException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.service.ProfileAnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;

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
                                "mentee",
                                "{\"startup_name\":\"Acme Labs\"}",
                                "PARSED",
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
                                .andExpect(jsonPath("$.status").value("PARSED"));
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
                                                "mentee",
                                                "{\"startup_name\":\"Confirmed Startup\"}",
                                                "CONFIRMED",
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
                when(profileAnalysisService.parsePreview(eq("individual_mentee"), eq(CONSENT), eq("auto"), any()))
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
                when(profileAnalysisService.parsePreview(eq("individual_mentee"), eq(new ConsentAnswer(false, null)), eq("auto"), any()))
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
                                eq("mentee"),
                                eq(CONSENT),
                                eq("auto"),
                                any())).thenReturn(new ProfileAnalysisResponse(
                                                analysisId,
                                                organizationId,
                                                null,
                                                "sample.pdf",
                                                "mentee",
                                                "{\"startup_name\":\"Sample Startup\"}",
                                                "PARSED",
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
                                "individual_mentee",
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
                                                "individual_mentee",
                                                "{\"startup_name\":\"Manual Startup\"}",
                                                "MANUAL",
                                                Instant.parse("2026-08-20T11:00:00Z"),
                                                "Manual profile created",
                                                "No OCR",
                                                Instant.parse("2026-08-20T10:58:00Z")));

                ProfileAnalysisResponse response = new ProfileAnalysisController(profileAnalysisService)
                                .createManual(organizationId, null, request);

                assertThat(response.id()).isEqualTo(analysisId);
                assertThat(response.profileType()).isEqualTo("individual_mentee");
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
                                any(), eq(documentId), eq("individual_mentee"), eq(CONSENT), eq("auto")))
                                .thenReturn(java.util.Map.of("current_role", "Ürün Yöneticisi"));

                mockMvc.perform(post("/api/profile-analyses/preview/documents/{id}", documentId)
                                .param("consentAccepted", "true")
                                .param("consentTextVersion", "cv-parsing-2026-11-13")
                                .with(user("test-user")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.current_role").value("Ürün Yöneticisi"));
        }

        @Test
        void returnsRecommendationsForConfirmedProfile() throws Exception {
                UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000205");
                ObjectMapper objectMapper = new ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode responseBody = objectMapper.createObjectNode();
                responseBody.put("result", "recommendations-ready");
                responseBody.put("count", 2);
                var items = responseBody.putArray("items");
                var first = items.addObject();
                first.put("mentorId", "00000000-0000-0000-0000-000000000900");
                var second = items.addObject();
                second.put("mentorId", "00000000-0000-0000-0000-000000000901");

                when(profileAnalysisService.recommendations(analysisId)).thenReturn(responseBody);

                mockMvc.perform(post("/api/profile-analyses/{id}/recommendations", analysisId)
                                .with(user("test-user")))
                                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                                .andExpect(status().isOk())
                                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        @Test
        void confirmsProfileAnalysis() throws Exception {
                UUID analysisId = UUID.fromString("00000000-0000-0000-0000-000000000206");

                when(profileAnalysisService.confirm(eq(analysisId), anyString()))
                                .thenReturn(new ProfileAnalysisResponse(
                                                analysisId,
                                                UUID.fromString("00000000-0000-0000-0000-000000000103"),
                                                null,
                                                "confirmed.pdf",
                                                "mentee",
                                                "{\"startup_name\":\"Confirmed Startup\"}",
                                                "CONFIRMED",
                                                Instant.parse("2026-08-20T12:00:00Z"),
                                                "Confirmed by user",
                                                "OCR summary",
                                                Instant.parse("2026-08-20T11:55:00Z")));

                mockMvc.perform(patch("/api/profile-analyses/{id}/confirmation", analysisId)
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
                                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
}
