package com.dps.teknokent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.domain.HealthStatus;
import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.responses.HealthComponentsResponse;
import com.dps.teknokent.dto.responses.HealthResponse;
import com.dps.teknokent.service.HealthService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private HealthService healthService;

	@Test
	void returnsOkWhenHealthIsUp() throws Exception {
		when(healthService.checkHealth()).thenReturn(
				new HealthResponse(HealthStatus.UP, "2026-01-01T00:00:00Z", "local",
						new HealthComponentsResponse(HealthStatus.UP, HealthStatus.UP)));

		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components.database").value("UP"))
				.andExpect(jsonPath("$.components.redis").value("UP"));
	}

	@Test
	void returnsServiceUnavailableWhenHealthIsDown() throws Exception {
		when(healthService.checkHealth()).thenReturn(
				new HealthResponse(HealthStatus.DOWN, "2026-01-01T00:00:00Z", "local",
						new HealthComponentsResponse(HealthStatus.DOWN, HealthStatus.DOWN)));

		mockMvc.perform(get("/api/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.components.database").value("DOWN"))
				.andExpect(jsonPath("$.components.redis").value("DOWN"));
	}

}
