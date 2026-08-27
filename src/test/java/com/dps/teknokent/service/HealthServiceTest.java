package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.dps.teknokent.domain.HealthStatus;
import com.dps.teknokent.entity.HealthProbe;
import com.dps.teknokent.repository.HealthProbeRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

	@Mock
	private HealthProbeRepository healthProbeRepository;
	@Mock
	private RedisConnectionFactory redisConnectionFactory;
	@Mock
	private RedisConnection redisConnection;

	@InjectMocks
	private HealthService healthService;

	@Test
	void returnsUpWhenDatabaseAndRedisAreReachable() {
		stubHealthyProbe();
		stubRedisPong();

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.UP);
		assertThat(response.components().database()).isEqualTo(HealthStatus.UP);
		assertThat(response.components().redis()).isEqualTo(HealthStatus.UP);
		assertThat(response.timestamp()).isNotBlank();
	}

	@Test
	void returnsDownWhenProbeIsMissing() {
		when(healthProbeRepository.findById(HealthProbe.PROBE_ID)).thenReturn(Optional.empty());
		stubRedisPong();

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
		assertThat(response.components().database()).isEqualTo(HealthStatus.DOWN);
		assertThat(response.components().redis()).isEqualTo(HealthStatus.UP);
	}

	@Test
	void returnsDownWhenProbeMessageIsBlank() {
		HealthProbe probe = new HealthProbe();
		probe.setId(HealthProbe.PROBE_ID);
		probe.setMessage("   ");
		probe.setUpdatedAt(Instant.now());
		when(healthProbeRepository.findById(HealthProbe.PROBE_ID)).thenReturn(Optional.of(probe));
		stubRedisPong();

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
		assertThat(response.components().database()).isEqualTo(HealthStatus.DOWN);
	}

	@Test
	void returnsDownWhenRepositoryThrows() {
		when(healthProbeRepository.findById(HealthProbe.PROBE_ID)).thenThrow(new RuntimeException("db unavailable"));
		stubRedisPong();

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
		assertThat(response.components().database()).isEqualTo(HealthStatus.DOWN);
	}

	@Test
	void returnsDownWhenRedisIsUnreachable() {
		stubHealthyProbe();
		when(redisConnectionFactory.getConnection()).thenThrow(new RuntimeException("redis unavailable"));

		var response = healthService.checkHealth();

		assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
		assertThat(response.components().database()).isEqualTo(HealthStatus.UP);
		assertThat(response.components().redis()).isEqualTo(HealthStatus.DOWN);
	}

	private void stubHealthyProbe() {
		HealthProbe probe = new HealthProbe();
		probe.setId(HealthProbe.PROBE_ID);
		probe.setMessage("ok");
		probe.setUpdatedAt(Instant.now());
		when(healthProbeRepository.findById(HealthProbe.PROBE_ID)).thenReturn(Optional.of(probe));
	}

	private void stubRedisPong() {
		when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
		when(redisConnection.ping()).thenReturn("PONG");
	}

}
