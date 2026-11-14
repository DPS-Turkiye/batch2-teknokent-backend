package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.PeerExperienceKind;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.PeerTopicLevel;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorExperience;
import com.dps.teknokent.entity.MentorProfile;

import jakarta.persistence.EntityManager;

/** The wizard's levels and experience cards survive a round trip, next to a Teknokent-style experience line. */
@DataJpaTest
class PeerMentorProfileCardsPersistenceTest {

	@Autowired
	private EntityManager entityManager;

	@Test
	void levelsAndCardsAreStoredAndReadBack() {
		AppUser mentor = new AppUser();
		mentor.setEmail("peer-cards@odtu-dev.invalid");
		mentor.setFullName("Zeynep Demir");
		mentor.setRole(UserRole.MENTOR);
		entityManager.persist(mentor);
		MentorProfile profile = MentorProfile.blankFor(mentor, 1);
		profile.getCategoryLevels().put(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED);
		profile.getCategoryLevels().put(PeerTopicCategory.INTERNATIONAL, PeerTopicLevel.BASIC);
		profile.getExperiences().add(new MentorExperience("Yazılım stajyeri", "Aselsan", null, null,
				PeerExperienceKind.INTERNSHIP, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 8, 1)));
		profile.getExperiences().add(new MentorExperience("Danışman", "Teknokent", "2020–2023", "Ürün"));
		entityManager.persist(profile);
		entityManager.flush();
		entityManager.clear();

		MentorProfile loaded = entityManager.find(MentorProfile.class, mentor.getId());

		assertThat(loaded.getCategoryLevels()).containsOnly(
				Map.entry(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED),
				Map.entry(PeerTopicCategory.INTERNATIONAL, PeerTopicLevel.BASIC));
		assertThat(loaded.getExperiences()).satisfiesExactly(
				card -> {
					assertThat(card.getKind()).isEqualTo(PeerExperienceKind.INTERNSHIP);
					assertThat(card.getStartedOn()).isEqualTo(LocalDate.of(2025, 6, 1));
					assertThat(card.getEndedOn()).isEqualTo(LocalDate.of(2025, 8, 1));
				},
				line -> {
					assertThat(line.getKind()).isNull();
					assertThat(line.getPeriod()).isEqualTo("2020–2023");
				});
	}
}
