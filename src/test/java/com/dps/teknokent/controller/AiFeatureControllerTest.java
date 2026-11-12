package com.dps.teknokent.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.service.AiFeatures;

@WebMvcTest(AiFeatureController.class)
@Import({ SecurityConfig.class, AiFeatures.class })
@TestPropertySource(properties = { "ai.features.syllabus=false", "ai.features.task-handover=false" })
class AiFeatureControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void reportsEachSwitchFromConfigurationWithUnsetOnesOn() throws Exception {
		mockMvc.perform(get("/api/ai-features").with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profileParsing").value(true))
				.andExpect(jsonPath("$.syllabus").value(false))
				.andExpect(jsonPath("$.matching").value(true))
				.andExpect(jsonPath("$.meetingReport").value(true))
				.andExpect(jsonPath("$.taskHandover").value(false));
	}

	@Test
	void needsAnAuthenticatedCaller() throws Exception {
		mockMvc.perform(get("/api/ai-features"))
				.andExpect(status().isUnauthorized());
	}
}
