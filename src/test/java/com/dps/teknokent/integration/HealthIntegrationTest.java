package com.dps.teknokent.integration;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.entity.HealthProbe;
import com.dps.teknokent.repository.HealthProbeRepository;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HealthProbeRepository healthProbeRepository;

	@Test
	void healthEndpointReadsProbeFromDatabase() throws Exception {
		HealthProbe probe = new HealthProbe();
		probe.setId(HealthProbe.PROBE_ID);
		probe.setMessage("ok");
		probe.setUpdatedAt(Instant.now());
		healthProbeRepository.save(probe);

		mockMvc.perform(get("/api/health"))
				.andExpect(status().is(anyOf(is(200), is(503))))
				.andExpect(jsonPath("$.version").value("local"))
				.andExpect(jsonPath("$.components.database").value("UP"))
				.andExpect(jsonPath("$.components.redis").value(anyOf(is("UP"), is("DOWN"))));
	}

}
