package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.entity.HealthProbe;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class HealthProbeRepositoryTest {

	@Autowired
	private HealthProbeRepository healthProbeRepository;

	@Test
	void savesAndFindsProbeById() {
		HealthProbe probe = new HealthProbe();
		probe.setId(HealthProbe.PROBE_ID);
		probe.setMessage("ok");
		probe.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));

		healthProbeRepository.save(probe);

		Optional<HealthProbe> found = healthProbeRepository.findById(HealthProbe.PROBE_ID);

		assertThat(found).isPresent();
		assertThat(found.get().getMessage()).isEqualTo("ok");
		assertThat(found.get().getUpdatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
	}

}
