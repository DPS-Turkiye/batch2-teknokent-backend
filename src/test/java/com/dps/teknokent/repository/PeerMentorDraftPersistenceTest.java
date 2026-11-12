package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PeerMentorDraft;

import jakarta.persistence.EntityManager;

@DataJpaTest
class PeerMentorDraftPersistenceTest {

	@Autowired
	private PeerMentorDraftRepository drafts;

	@Autowired
	private EntityManager entityManager;

	@Test
	void aDraftIsStoredAsJsonWithItsSaveTimeAndReplacedInPlace() {
		AppUser student = new AppUser();
		student.setEmail("draft-student@odtu-dev.invalid");
		student.setFullName("Zeynep Demir");
		student.setRole(UserRole.MENTOR);
		entityManager.persist(student);
		PeerMentorDraft draft = new PeerMentorDraft(student.getId());
		draft.setPayload("{\"step\":\"topics\",\"categories\":[\"CAREER_INTERNSHIP\"]}");
		drafts.saveAndFlush(draft);
		entityManager.clear();

		PeerMentorDraft loaded = drafts.findById(student.getId()).orElseThrow();
		assertThat(loaded.getPayload()).contains("CAREER_INTERNSHIP");
		assertThat(loaded.getUpdatedAt()).isNotNull();

		loaded.setPayload("{\"step\":\"preview\"}");
		drafts.saveAndFlush(loaded);
		entityManager.clear();

		assertThat(drafts.count()).isEqualTo(1);
		assertThat(drafts.findById(student.getId()).orElseThrow().getPayload()).contains("preview");
	}
}
