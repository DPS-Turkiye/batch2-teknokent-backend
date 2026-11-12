package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The edition filter in the real security chain: the other edition's endpoints are a 404 before authentication,
 * for anonymous callers and for the frontend's service credential alike.
 */
class EditionSecurityTest {

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	class DefaultEdition {

		@Autowired
		private MockMvc mockMvc;

		@Test
		void closesTheOdtuEndpointsToAnonymousCallers() throws Exception {
			mockMvc.perform(get("/api/peer-mentoring/mentors"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.status").value(404));
			// Public in the ODTÜ edition, so only the edition filter can close it.
			mockMvc.perform(get("/api/lti/jwks")).andExpect(status().isNotFound());
		}

		@Test
		void closesTheOdtuEndpointsToTheServiceCredential() throws Exception {
			mockMvc.perform(get("/api/office-hours/requests").with(httpBasic("test", "test")))
					.andExpect(status().isNotFound());
			mockMvc.perform(post("/api/auth/institution/odtu/start").with(httpBasic("test", "test")))
					.andExpect(status().isNotFound());
		}

		@Test
		void keepsTheTeknokentEndpointsBehindTheirUsualChecks() throws Exception {
			mockMvc.perform(get("/api/assistant/bots")).andExpect(status().isUnauthorized());
			mockMvc.perform(post("/api/auth/register/mentor")).andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@SpringBootTest(properties = "app.edition=ODTU")
	@AutoConfigureMockMvc
	class OdtuEdition {

		@Autowired
		private MockMvc mockMvc;

		@Test
		void closesTheTeknokentEndpoints() throws Exception {
			mockMvc.perform(get("/api/assistant/bots")).andExpect(status().isNotFound());
			mockMvc.perform(post("/api/auth/register/mentor")).andExpect(status().isNotFound());
			mockMvc.perform(post("/api/auth/institution/otg/start").with(httpBasic("test", "test")))
					.andExpect(status().isNotFound());
		}

		@Test
		void keepsTheOdtuEndpointsBehindTheirUsualChecks() throws Exception {
			mockMvc.perform(get("/api/peer-mentoring/mentors")).andExpect(status().isUnauthorized());
			mockMvc.perform(get("/api/lti/jwks"))
					.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(404));
		}
	}
}
