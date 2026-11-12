package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
		draft.setUpdatedAt(Instant.parse("2026-11-12T11:05:00Z"));
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

		assertThat(drafts.deleteByUserId(student.getId())).isEqualTo(1);
		assertThat(drafts.deleteByUserId(student.getId())).isZero();
	}

	/** PostgreSQL only (ON CONFLICT): run with the datasource pointed at PostgreSQL; skipped on the H2 test database. */
	@Test
	@EnabledIfSystemProperty(named = "spring.datasource.url", matches = "jdbc:postgresql:.*")
	void aSecondFirstSaveLandsOnTheSameRow() {
		AppUser student = student("draft-race@odtu-dev.invalid");
		Instant now = Instant.parse("2026-11-12T11:05:00Z");

		assertThat(drafts.insertIfAbsent(student.getId(), now)).isEqualTo(1);
		assertThat(drafts.insertIfAbsent(student.getId(), now)).isZero();
		PeerMentorDraft row = drafts.findById(student.getId()).orElseThrow();
		row.setPayload("{\"step\":\"topics\"}");
		drafts.saveAndFlush(row);
		entityManager.clear();

		assertThat(drafts.findById(student.getId()).orElseThrow().getPayload()).contains("topics");
	}

	private AppUser student(String email) {
		AppUser student = new AppUser();
		student.setEmail(email);
		student.setFullName("Zeynep Demir");
		student.setRole(UserRole.MENTOR);
		entityManager.persist(student);
		return student;
	}
}
