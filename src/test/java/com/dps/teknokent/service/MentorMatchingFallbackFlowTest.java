package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorRecommendationResult;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;

import jakarta.persistence.EntityManager;

/**
 * Matching with pgvector unavailable (off here, as when the database or the embedding provider is down): the
 * deterministic fallback must still give a usable, explained shortlist from approved mentors only.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "matching.pgvector.enabled=false")
class MentorMatchingFallbackFlowTest {

	@Autowired
	private MentorRecommendationService recommendations;

	@Autowired
	private EntityManager entityManager;

	private AppUser payments;
	private AppUser growth;
	private AppUser pending;

	@BeforeEach
	void mentors() {
		payments = persistMentor("Ayla Payments", UserAccountStatus.APPROVED, List.of("Fintech", "Payments"), "Fintech");
		growth = persistMentor("Burak Growth", UserAccountStatus.APPROVED, List.of("Growth marketing"), "E-commerce");
		for (int i = 0; i < 5; i++) {
			persistMentor("Cem Other " + i, UserAccountStatus.APPROVED, List.of("Hardware"), "Manufacturing");
		}
		// The best keyword match on paper, but not approved: must never be suggested.
		pending = persistMentor("Deniz Pending", UserAccountStatus.PENDING,
				List.of("Fintech", "Payments", "Banking"), "Fintech");
		entityManager.flush();
		entityManager.clear();
	}

	@Test
	void fallbackRanksByTheRequestAndExplainsEachMatch() {
		MentorRecommendationResult result = recommendations.recommend(fintechRequest(null));

		assertThat(result.matchingMethod()).isEqualTo("DETERMINISTIC");
		assertThat(result.matchingModel()).isEqualTo("keyword-overlap-v1");
		assertThat(result.mentors()).hasSize(5); // a shortlist, not the whole pool of 7
		assertThat(result.mentors().getFirst().id()).isEqualTo(payments.getId());
		assertThat(result.mentors()).extracting(MentorProfileResponse::id).doesNotContain(pending.getId());
		assertThat(result.mentors().getFirst().recommendationReasons())
				.anyMatch(reason -> reason.contains("Fintech") || reason.contains("Payments"));
		assertThat(result.mentors()).allSatisfy(mentor -> assertThat(mentor.recommendationReasons()).isNotEmpty());
	}

	@Test
	void theCurrentMentorIsLeftOutWhenTheTeamAsksForAnAlternative() {
		MentorRecommendationResult result = recommendations.recommend(fintechRequest(payments.getId()));

		assertThat(result.mentors()).extracting(MentorProfileResponse::id).doesNotContain(payments.getId());
	}

	@Test
	void aProgramPoolLimitsTheChoiceToItsOwnApprovedMentors() {
		MentorRecommendationResult result = recommendations.recommendAmong(fintechRequest(null),
				List.of(growth.getId(), pending.getId()));

		// The pool's only approved mentor, though the pending one matches the request better.
		assertThat(result.mentors()).extracting(MentorProfileResponse::id).containsExactly(growth.getId());
	}

	@Test
	void anEmptyPoolGivesAnEmptyShortlistNotAnError() {
		MentorRecommendationResult result = recommendations.recommendAmong(fintechRequest(null),
				List.of(UUID.randomUUID()));

		assertThat(result.mentors()).isEmpty();
		assertThat(result.matchingMethod()).isEqualTo("DETERMINISTIC");
	}

	private static RecommendMentorsRequest fintechRequest(UUID excludedMentorId) {
		return new RecommendMentorsRequest("Fintech", "Payments infrastructure for our fintech app", null, null,
				null, "Fintech", "MVP", "Payments platform", List.of("Payments"), excludedMentorId);
	}

	private AppUser persistMentor(String name, UserAccountStatus status, List<String> specialties, String sector) {
		AppUser user = new AppUser();
		user.setEmail(name.toLowerCase().replace(' ', '.') + "@mentor.test");
		user.setFullName(name);
		user.setRole(UserRole.MENTOR);
		user.setStatus(status);
		entityManager.persist(user);

		MentorProfile profile = new MentorProfile();
		profile.setUser(user);
		profile.setInitials(name.substring(0, 2).toUpperCase());
		profile.setProfessionalTitle("Mentor");
		profile.setCompany("Teknokent");
		profile.setAvailabilityLabel("Mentorluğa açık");
		profile.setResponseTimeLabel("~48 saat");
		profile.setMeetingMode("Online");
		profile.setCommunicationPreference("Mesajlaşma");
		profile.setMentoringStyle("İşbirlikçi");
		profile.setFitLabel("Yüksek");
		profile.setExperienceLevel("10 yıl");
		profile.setSectorExperience(sector);
		profile.setStartupStages("MVP");
		profile.setSpecialties(new ArrayList<>(specialties));
		entityManager.persist(profile);
		return user;
	}
}
