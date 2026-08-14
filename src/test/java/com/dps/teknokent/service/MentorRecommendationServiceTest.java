package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorRecommendationServiceTest {

	@Mock
	private MentorProfileService mentorProfileService;
	@Mock
	private PgVectorMentorRetrievalService pgVectorRetrievalService;

	private MentorRecommendationService recommendationService;

	@BeforeEach
	void setUp() {
		recommendationService = new MentorRecommendationService(mentorProfileService, pgVectorRetrievalService);
		ReflectionTestUtils.setField(recommendationService, "matcherUrl", "http://127.0.0.1:1");
		ReflectionTestUtils.setField(recommendationService, "provider", "openrouter");
		ReflectionTestUtils.setField(recommendationService, "openRouterModel", "openai/text-embedding-3-small");
		ReflectionTestUtils.setField(recommendationService, "openAiModel", "text-embedding-3-small");
		try {
			org.mockito.Mockito.lenient().when(pgVectorRetrievalService.shortlist(
					org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(),
					org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
					org.mockito.ArgumentMatchers.anyInt()))
					.thenAnswer(invocation -> invocation.getArgument(1));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	@Test
	void fallsBackToDeterministicRankingWhenMatcherIsUnavailable() {
		MentorProfileResponse financeMentor = mentor("Zeynep Finans", "Finans ve yatırım", List.of("Yatırım"));
		MentorProfileResponse softwareMentor = mentor(
				"Ali Yazılım", "Yazılım mimarı", List.of("Yazılım", "Teknoloji", "Yapay zeka"));
		when(mentorProfileService.findAll()).thenReturn(List.of(financeMentor, softwareMentor));

		var result = recommendationService.recommend(new RecommendMentorsRequest(
				"Yazılım ve teknoloji",
				"Yazılım yazılım mimarisi ve yapay zeka desteği",
				"THIS_WEEK",
				"SINGLE_MEETING",
				"ONLINE",
				"Sağlık Teknolojileri",
				"MVP",
				"B2B SaaS platformu",
				List.of("Java", "Python"),
				null));

		assertThat(result.matchingMethod()).isEqualTo("DETERMINISTIC");
		assertThat(result.mentors()).extracting(MentorProfileResponse::fullName)
				.containsExactly("Ali Yazılım", "Zeynep Finans");
		assertThat(result.mentors().getFirst().recommendationReasons())
				.contains("Talebinizle örtüşen uzmanlık: Yapay zeka");
	}

	@Test
	void selectsOnlyConfirmedEvidenceRelevantToTheCurrentRequest() {
		MentorProfileResponse mentor = mentor(
				"Ali Yazılım", "Yazılım mimarı", List.of("Yapay zeka", "Fiyatlandırma"),
				List.of(
						new MentorEvidenceResponse("Teknoloji", "Yapay zeka sistemleri kurdu",
								"Üretim ortamında yapay zeka ve MLOps platformları geliştirdi.", 2, "confirmed-v2"),
						new MentorEvidenceResponse("Ticari", "Fiyatlandırma projeleri yönetti",
								"B2B SaaS fiyatlandırma stratejileri hazırladı.", 4, "confirmed-v2")));
		when(mentorProfileService.findAll()).thenReturn(List.of(mentor));

		var result = recommendationService.recommend(new RecommendMentorsRequest(
				"Yapay zeka", "Yapay zeka mimarisi desteği", "THIS_WEEK", "SINGLE_MEETING",
				"ONLINE", "Teknoloji", "MVP", "Dijital platform", List.of(), null));

		assertThat(result.mentors().getFirst().recommendationEvidence())
				.extracting(MentorEvidenceResponse::claim)
				.containsExactly("Yapay zeka sistemleri kurdu");
	}

	private MentorProfileResponse mentor(String fullName, String title, List<String> specialties) {
		return mentor(fullName, title, specialties, List.of());
	}

	private MentorProfileResponse mentor(
			String fullName, String title, List<String> specialties, List<MentorEvidenceResponse> evidence) {
		return new MentorProfileResponse(UUID.randomUUID(), fullName, "mentor@example.com", "ME", title,
				"DPS", "Müsait", "24 saat", "Online", "E-posta", "Yapılandırılmış", "Yüksek",
				"Kıdemli", "Teknoloji", "MVP", specialties, List.of(), evidence);
	}
}
