package com.dps.teknokent.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import com.dps.teknokent.dto.responses.StudentProfileResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorRecommendationServiceTest {

	@Mock
	private MentorProfileService mentorProfileService;
	@Mock
	private PgVectorMentorRetrievalService pgVectorRetrievalService;
	@Mock
	private MenteeProgramService menteeProgramService;

	private MentorRecommendationService recommendationService;

	@BeforeEach
	void setUp() {
		recommendationService = new MentorRecommendationService(mentorProfileService, pgVectorRetrievalService,
				menteeProgramService);
		ReflectionTestUtils.setField(recommendationService, "provider", "openrouter");
		ReflectionTestUtils.setField(recommendationService, "openRouterModel", "openai/text-embedding-3-small");
		ReflectionTestUtils.setField(recommendationService, "openAiModel", "text-embedding-3-small");
		try {
			org.mockito.Mockito.lenient().when(pgVectorRetrievalService.shortlist(
					org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(),
					org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
					org.mockito.ArgumentMatchers.anyInt()))
					.thenThrow(new IllegalStateException("pgvector unavailable"));
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

	@Test
	void preservesPgvectorSimilarityOrderAndAddsRequestSpecificReasons() throws Exception {
		MentorProfileResponse financeMentor = mentor("Zeynep Finans", "Finans", List.of("Yatırım"));
		MentorProfileResponse softwareMentor = mentor("Ali Yazılım", "Mimar", List.of("Yapay zeka"));
		List<MentorProfileResponse> pool = List.of(financeMentor, softwareMentor);
		when(mentorProfileService.findAll()).thenReturn(pool);
		when(pgVectorRetrievalService.shortlist(
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(pool),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyInt()))
				.thenReturn(List.of(softwareMentor, financeMentor));

		var result = recommendationService.recommend(new RecommendMentorsRequest(
				"Yapay zeka", "Yapay zeka mimarisi", "THIS_WEEK", "SINGLE_MEETING",
				"ONLINE", "Teknoloji", "MVP", "Platform", List.of("Java"), null));

		assertThat(result.matchingMethod()).isEqualTo("PGVECTOR_SEMANTIC");
		assertThat(result.mentors()).extracting(MentorProfileResponse::fullName)
				.containsExactly("Ali Yazılım", "Zeynep Finans");
	}

	@Test
	void addsGenericFallbackReasonWhenNoRequestSpecificReasonMatches() throws Exception {
		MentorProfileResponse mentor = mentor("Zeynep Finans", "Finans", List.of("Yatırım"));
		List<MentorProfileResponse> pool = List.of(mentor);
		when(mentorProfileService.findAll()).thenReturn(pool);
		when(pgVectorRetrievalService.shortlist(
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(pool),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyInt()))
				.thenReturn(pool);

		var result = recommendationService.recommend(new RecommendMentorsRequest(
				"İş modeli ve strateji", "Gelir modeli oluşturma desteği", "WHENEVER", "SINGLE_MEETING",
				"ASYNCHRONOUS", "Sağlık", "Fikir", "Mobil uygulama", List.of(), null));

		assertThat(result.mentors().getFirst().recommendationReasons())
				.containsExactly("İş modeli ve strateji talebiyle uzmanlık yakınlığı");
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


	private static MentorProfileResponse withStudent(MentorProfileResponse m, StudentProfileResponse student) {
		return new MentorProfileResponse(m.id(), m.fullName(), m.email(), m.initials(), m.professionalTitle(),
				m.company(), m.availabilityLabel(), m.responseTimeLabel(), m.meetingMode(),
				m.communicationPreference(), m.mentoringStyle(), m.fitLabel(), m.experienceLevel(),
				m.sectorExperience(), m.startupStages(), m.specialties(), m.recommendationReasons(),
				m.recommendationEvidence(), m.menteeCapacity(), m.about(), m.defaultMeetingDurationMinutes(),
				m.meetingBufferMinutes(), m.meetingFrequency(), m.prepFileRequirement(), m.languages(),
				m.activeMenteeCount(), m.completedMentorshipCount(), m.programNames(), m.programs(),
				m.experiences(), m.offerings(), student);
	}

	private static RecommendMentorsRequest studentRequest(String supportArea, String summary) {
		return new RecommendMentorsRequest(supportArea, summary, "THIS_WEEK", "SINGLE_MEETING", "ONLINE", null,
				null, null, List.of(), null);
	}

	@Test
	void ranksAStudentMentorByTheirStudiesAndSaysWhy() {
		MentorProfileResponse plain = mentor("Deniz Öğrenci", "Öğrenci mentor", List.of("Dersler"));
		MentorProfileResponse abroad = withStudent(mentor("Ece Öğrenci", "Öğrenci mentor", List.of("Staj")),
				new StudentProfileResponse("Bilgisayar Mühendisliği", "4. sınıf", List.of("CENG 140"),
						List.of("Kampüs etkinlik uygulaması"), "TU Delft, 2025 güz"));
		when(mentorProfileService.findAll()).thenReturn(List.of(plain, abroad));

		var result = recommendationService.recommend(studentRequest(
				"Erasmus & yurt dışı", "Erasmus başvurusu ve CENG 140 dersi için destek istiyorum."));

		assertThat(result.mentors()).extracting(MentorProfileResponse::fullName)
				.startsWith("Ece Öğrenci");
		assertThat(result.mentors().getFirst().recommendationReasons()).contains(
				"Bu derste güçlü: CENG 140",
				"Yurt dışı deneyimi var: TU Delft, 2025 güz");
	}

	@Test
	void namesASimilarProjectAndSkipsErasmusWhenTheStudentIsNotGoingAbroad() throws Exception {
		MentorProfileResponse student = withStudent(mentor("Ece Öğrenci", "Öğrenci mentor", List.of("Proje")),
				new StudentProfileResponse("Endüstri Mühendisliği", "3. sınıf", List.of(),
						List.of("Kampüs etkinlik uygulaması"), "TU Delft, 2025 güz"));
		when(mentorProfileService.findAll()).thenReturn(List.of(student));

		var result = recommendationService.recommend(studentRequest(
				"Proje & girişimcilik", "Kampüs için bir etkinlik uygulaması geliştiriyorum."));

		assertThat(result.mentors().getFirst().recommendationReasons())
				.contains("Benzer bir projede çalıştı: Kampüs etkinlik uygulaması")
				.noneMatch(reason -> reason.startsWith("Yurt dışı"));
	}

	@Test
	void recommendFor_ranksOnlyTheMenteesProgramMentorsWithoutTheExcludedOne() {
		UUID mentee = UUID.randomUUID();
		MentorProfileResponse inProgram = mentor("Ali Yazılım", "Yazılım mimarı", List.of("Yazılım"));
		MentorProfileResponse current = mentor("Ece Ürün", "Ürün yöneticisi", List.of("Ürün"));
		MentorProfileResponse outside = mentor("Zeynep Finans", "Finans", List.of("Yatırım"));
		when(mentorProfileService.findAll()).thenReturn(List.of(inProgram, current, outside));
		when(menteeProgramService.reachableMentorIds(mentee))
				.thenReturn(Optional.of(Set.of(inProgram.id(), current.id())));

		var result = recommendationService.recommendFor(mentee, request(current.id()));

		assertThat(result.mentors()).extracting(MentorProfileResponse::fullName).containsExactly("Ali Yazılım");
	}

	@Test
	void recommendFor_ranksEveryoneWhenNothingLimitsTheCaller() {
		UUID coordinator = UUID.randomUUID();
		MentorProfileResponse first = mentor("Ali Yazılım", "Yazılım mimarı", List.of("Yazılım"));
		MentorProfileResponse second = mentor("Zeynep Finans", "Finans", List.of("Yatırım"));
		when(mentorProfileService.findAll()).thenReturn(List.of(first, second));
		when(menteeProgramService.reachableMentorIds(coordinator)).thenReturn(Optional.empty());

		var result = recommendationService.recommendFor(coordinator, request(null));

		assertThat(result.mentors()).hasSize(2);
	}

	private static RecommendMentorsRequest request(UUID excludedMentorId) {
		return new RecommendMentorsRequest("Yazılım", "Yazılım mimarisi desteği", "THIS_WEEK", "SINGLE_MEETING",
				"ONLINE", null, "MVP", null, List.of(), excludedMentorId);
	}
}
