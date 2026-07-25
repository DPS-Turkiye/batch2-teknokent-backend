package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.HealthStatus;
import com.dps.teknokent.entity.HealthProbe;
import com.dps.teknokent.repository.HealthProbeRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

	@Mock
	private HealthProbeRepository healthProbeRepository;

	@InjectMocks
	private HealthService healthService;

	@Test
	void returnsUpWhenProbeExists() {
		HealthProbe probe = new HealthProbe();
		probe.setId(HealthProbe.PROBE_ID);
		probe.setMessage("ok");
		probe.setUpdatedAt(Instant.now());
		when(healthProbeRepository.findById(HealthProbe.PROBE_ID)).thenReturn(Optional.of(probe));

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.UP);
		assertThat(response.components().database()).isEqualTo(HealthStatus.UP);
		assertThat(response.timestamp()).isNotBlank();
	}

	@Test
	void returnsDownWhenProbeIsMissing() {
		when(healthProbeRepository.findById(HealthProbe.PROBE_ID)).thenReturn(Optional.empty());

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
		assertThat(response.components().database()).isEqualTo(HealthStatus.DOWN);
	}

	@Test
	void returnsDownWhenProbeMessageIsBlank() {
		HealthProbe probe = new HealthProbe();
		probe.setId(HealthProbe.PROBE_ID);
		probe.setMessage("   ");
		probe.setUpdatedAt(Instant.now());
		when(healthProbeRepository.findById(HealthProbe.PROBE_ID)).thenReturn(Optional.of(probe));

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
		assertThat(response.components().database()).isEqualTo(HealthStatus.DOWN);
	}

	@Test
	void returnsDownWhenRepositoryThrows() {
		when(healthProbeRepository.findById(HealthProbe.PROBE_ID)).thenThrow(new RuntimeException("db unavailable"));

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
		assertThat(response.components().database()).isEqualTo(HealthStatus.DOWN);
	}

}
