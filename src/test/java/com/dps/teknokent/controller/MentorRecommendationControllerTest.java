package com.dps.teknokent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorMatchingMethod;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorRecommendationResult;
import com.dps.teknokent.service.MentorRecommendationService;

@WebMvcTest(MentorRecommendationController.class)
@Import(SecurityConfig.class)
class MentorRecommendationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MentorRecommendationService mentorRecommendationService;

	@Test
	void requiresAuthenticationForRecommendations() throws Exception {
		mockMvc.perform(post("/api/mentor-recommendations")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "supportArea": "Product strategy",
						  "summary": "We need guidance on product experimentation"
						}
						"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void returnsRecommendedMentorsWithHeaders() throws Exception {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		Instant generatedAt = Instant.parse("2026-09-01T12:00:00Z");

		when(mentorRecommendationService.recommendFor(any(), any(RecommendMentorsRequest.class)))
				.thenReturn(new MentorRecommendationResult(
						MentorMatchingMethod.PGVECTOR_SEMANTIC,
						"text-embedding-3-small",
						generatedAt,
						List.of(new MentorProfileResponse(
								mentorId,
								"Dr. Kemal Arslan",
								"kemal.arslan@teknokent.demo",
								"KA",
								"Ürün & Araştırma Direktörü",
								"Trendyol",
								"Bu hafta müsait",
								"~24 saat",
								"Online",
								"Mesajlaşma açık",
								"Yapılandırılmış & Uygulamalı",
								"Çok yüksek",
								"Kullanıcı testi, UX",
								"E-ticaret, SaaS",
								"Seed & MVP",
								List.of("Kullanıcı araştırması", "Ürün stratejisi"),
								List.of("Yüksek uyum"),
								List.of(new MentorEvidenceResponse(
										"Mentor experience and product strategy",
										"Aynı alanı desteklediği ve deneyim sahibi olduğu kanıtı",
										"Bu mentor, 5 yıldır ürün stratejisi kurucularına destek veriyor.",
										4,
										"v1"))))));

		mockMvc.perform(post("/api/mentor-recommendations")
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "supportArea": "Product strategy",
						  "summary": "We need guidance on product experimentation",
						  "urgency": "THIS_WEEK",
						  "mentorshipFormat": "SINGLE_MEETING",
						  "meetingMode": "ONLINE",
						  "startupSector": "SaaS",
						  "startupStage": "Erken aşama",
						  "startupSummary": "We are building a B2B analytics product",
						  "startupTechnologies": ["Java", "Spring Boot"],
						  "excludedMentorId": "00000000-0000-0000-0000-000000000999"
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Matching-Method", "PGVECTOR_SEMANTIC"))
				.andExpect(header().string("X-Matching-Model", "text-embedding-3-small"))
				.andExpect(header().string("X-Recommendation-Generated-At", generatedAt.toString()))
				.andExpect(jsonPath("$[0].id").value(mentorId.toString()))
				.andExpect(jsonPath("$[0].fullName").value("Dr. Kemal Arslan"))
				.andExpect(jsonPath("$[0].specialties[0]").value("Kullanıcı araştırması"));
		ArgumentCaptor<RecommendMentorsRequest> sent = ArgumentCaptor.forClass(RecommendMentorsRequest.class);
		verify(mentorRecommendationService).recommendFor(any(), sent.capture());
		assertThat(sent.getValue().urgency()).isEqualTo(MentorshipRequestUrgency.THIS_WEEK);
		assertThat(sent.getValue().mentorshipFormat()).isEqualTo(MentorshipRequestFormat.SINGLE_MEETING);
		assertThat(sent.getValue().meetingMode()).isEqualTo(MeetingMode.ONLINE);
		assertThat(sent.getValue().startupStage()).isEqualTo("Erken aşama");
	}

	@Test
	void anUrgencyFormatOrModeTheAppDoesNotOfferIsABadRequest() throws Exception {
		for (String field : List.of("urgency", "mentorshipFormat", "meetingMode")) {
			mockMvc.perform(post("/api/mentor-recommendations")
					.with(user("test-user"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"supportArea\":\"Ürün\",\"summary\":\"Deney tasarımı\",\"%s\":\"MEDIUM\"}"
							.formatted(field)))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(mentorRecommendationService);
	}

	// Every one of these texts is sent to the paid embeddings API.
	@Test
	void oversizedTextsAndTooManyTechnologiesAreRefusedBeforeAnyEmbeddingIsBought() throws Exception {
		List<String> bodies = List.of(
				"{\"supportArea\":\"%s\",\"summary\":\"Deney\"}".formatted("a".repeat(256)),
				"{\"supportArea\":\"Ürün\",\"summary\":\"%s\"}".formatted("a".repeat(5001)),
				"{\"supportArea\":\"Ürün\",\"summary\":\"Deney\",\"startupSummary\":\"%s\"}"
						.formatted("a".repeat(5001)),
				"{\"supportArea\":\"Ürün\",\"summary\":\"Deney\",\"startupStage\":\"%s\"}"
						.formatted("a".repeat(501)),
				"{\"supportArea\":\"Ürün\",\"summary\":\"Deney\",\"startupTechnologies\":[\"%s\"]}"
						.formatted("a".repeat(256)),
				"{\"supportArea\":\"Ürün\",\"summary\":\"Deney\",\"startupTechnologies\":%s}"
						.formatted(java.util.stream.IntStream.range(0, 51).mapToObj(i -> "\"T" + i + "\"").toList()));
		for (String body : bodies) {
			mockMvc.perform(post("/api/mentor-recommendations")
					.with(user("test-user"))
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(mentorRecommendationService);
	}

	@Test
	void rejectsRecommendationRequestWhenRequiredFieldsAreBlank() throws Exception {
		mockMvc.perform(post("/api/mentor-recommendations")
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "supportArea": "",
						  "summary": ""
						}
						"""))
				.andExpect(status().isBadRequest());
	}
}
