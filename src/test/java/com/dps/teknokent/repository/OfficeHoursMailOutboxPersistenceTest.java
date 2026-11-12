package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.entity.OfficeHoursMailOutbox;

import jakarta.persistence.EntityManager;

@DataJpaTest
class OfficeHoursMailOutboxPersistenceTest {

	private static final Instant NOW = Instant.parse("2026-11-12T20:00:00Z");

	@Autowired
	private OfficeHoursMailOutboxRepository outbox;

	@Autowired
	private EntityManager entityManager;

	private OfficeHoursMailOutbox queued(Instant nextAttemptAt) {
		OfficeHoursMailOutbox mail = new OfficeHoursMailOutbox();
		mail.setId(UUID.randomUUID());
		mail.setToAddress("ayse@metu.edu.tr");
		mail.setSubject("Konu");
		mail.setBody("Gövde");
		mail.setNextAttemptAt(nextAttemptAt);
		mail.setCreatedAt(NOW);
		return outbox.saveAndFlush(mail);
	}

	@Test
	void onlyDueMailsAreReadAndASecondClaimOfTheSameAttemptLoses() {
		OfficeHoursMailOutbox due = queued(NOW.minusSeconds(60));
		queued(NOW.plusSeconds(60));
		entityManager.clear();

		assertThat(outbox.findTop50ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(NOW))
				.extracting(OfficeHoursMailOutbox::getId).containsExactly(due.getId());

		Instant lease = NOW.plusSeconds(300);
		assertThat(outbox.claim(due.getId(), due.getNextAttemptAt(), lease)).isEqualTo(1);
		assertThat(outbox.claim(due.getId(), due.getNextAttemptAt(), lease)).isZero();
		entityManager.clear();

		OfficeHoursMailOutbox claimed = outbox.findById(due.getId()).orElseThrow();
		assertThat(claimed.getAttempts()).isEqualTo(1);
		assertThat(claimed.getNextAttemptAt()).isEqualTo(lease);
		assertThat(outbox.findTop50ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(NOW)).isEmpty();

		assertThat(outbox.deleteSent(due.getId())).isEqualTo(1);
		entityManager.clear();
		assertThat(outbox.findById(due.getId())).isEmpty();
	}
}
