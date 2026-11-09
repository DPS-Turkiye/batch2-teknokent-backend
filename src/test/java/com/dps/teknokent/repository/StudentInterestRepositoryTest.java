package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.StudentInterest;

import jakarta.persistence.EntityManager;

@DataJpaTest
class StudentInterestRepositoryTest {

	@Autowired
	private StudentInterestRepository repository;
	@Autowired
	private EntityManager entityManager;

	@Test
	void keepsTopicsAndSubtopicsInTheOrderTheStudentPickedThem() {
		AppUser student = new AppUser();
		student.setEmail("interest-student@metu.edu.tr");
		student.setFullName("Ayşe Yılmaz");
		student.setRole(UserRole.MENTEE);
		entityManager.persist(student);
		StudentInterest interest = new StudentInterest(student.getId());
		interest.setTopics(new ArrayList<>(List.of(PeerTopicCategory.INTERNATIONAL, PeerTopicCategory.CAREER_INTERNSHIP)));
		interest.setSubtopics(new ArrayList<>(List.of("fixture-exchange", "fixture-cv")));
		interest.setGoal("Erasmus ve staj");
		interest.setCompleted(true);
		interest.setUpdatedAt(Instant.now());
		repository.saveAndFlush(interest);
		entityManager.clear();

		StudentInterest read = repository.findById(student.getId()).orElseThrow();

		assertThat(read.getTopics()).containsExactly(PeerTopicCategory.INTERNATIONAL, PeerTopicCategory.CAREER_INTERNSHIP);
		assertThat(read.getSubtopics()).containsExactly("fixture-exchange", "fixture-cv");
		assertThat(read.getGoal()).isEqualTo("Erasmus ve staj");
		assertThat(read.isCompleted()).isTrue();
		assertThat(read.isSkipped()).isFalse();
	}
}
