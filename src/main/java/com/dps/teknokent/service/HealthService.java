package com.dps.teknokent.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.HealthStatus;
import com.dps.teknokent.dto.responses.HealthComponentsResponse;
import com.dps.teknokent.dto.responses.HealthResponse;
import com.dps.teknokent.entity.HealthProbe;
import com.dps.teknokent.repository.HealthProbeRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HealthService {

	private final HealthProbeRepository healthProbeRepository;
	private final RedisConnectionFactory redisConnectionFactory;

	@Value("${APP_VERSION:local}")
	private String appVersion;

	@Transactional(readOnly = true)
	public HealthResponse checkHealth() {
		HealthStatus databaseStatus = checkDatabase();
		HealthStatus redisStatus = checkRedis();
		HealthStatus status = databaseStatus.isUp() && redisStatus.isUp()
				? HealthStatus.UP
				: HealthStatus.DOWN;

		return new HealthResponse(status, Instant.now().toString(), appVersion,
				new HealthComponentsResponse(databaseStatus, redisStatus));
	}

	private HealthStatus checkDatabase() {
		try {
			HealthProbe probe = healthProbeRepository.findById(HealthProbe.PROBE_ID).orElse(null);
			if (probe == null || probe.getMessage() == null || probe.getMessage().isBlank()) {
				return HealthStatus.DOWN;
			}
			return HealthStatus.UP;
		} catch (Exception exception) {
			return HealthStatus.DOWN;
		}
	}

	private HealthStatus checkRedis() {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			String pong = connection.ping();
			if (pong == null || pong.isBlank()) {
				return HealthStatus.DOWN;
			}
			return HealthStatus.UP;
		} catch (Exception exception) {
			return HealthStatus.DOWN;
		}
	}

}
