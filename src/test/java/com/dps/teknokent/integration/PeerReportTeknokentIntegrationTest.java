package com.dps.teknokent.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** The Teknokent deployment has no peer mentoring: every report endpoint answers 404, with or without credentials. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.edition=TEKNOKENT")
class PeerReportTeknokentIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void everyReportEndpointIsClosed() throws Exception {
		UUID id = UUID.randomUUID();
		String actorId = id.toString();
		String actor = "{\"actorId\":\"" + actorId + "\"}";
		String base = "/api/peer-mentoring/reports";
		List<AbstractMockHttpServletRequestBuilder<?>> requests = List.of(
				get(base + "/meetings").param("from", "2026-11-09").param("to", "2026-11-13").param("actorId", actorId),
				get(base + "/meetings/{id}", id).param("actorId", actorId),
				put(base + "/meetings/{id}", id).contentType(MediaType.APPLICATION_JSON).content(actor),
				post(base + "/meetings/{id}/submit", id).param("actorId", actorId),
				patch(base + "/meetings/{id}/actions/{actionId}", id, id)
						.contentType(MediaType.APPLICATION_JSON).content(actor),
				get(base + "/days").param("week", "2026-11-09").param("actorId", actorId),
				get(base + "/days/{date}", "2026-11-12").param("actorId", actorId),
				put(base + "/days/{date}", "2026-11-12").contentType(MediaType.APPLICATION_JSON).content(actor),
				post(base + "/days/{date}/submit", "2026-11-12").param("actorId", actorId),
				post(base + "/activities").contentType(MediaType.APPLICATION_JSON).content(actor),
				put(base + "/activities/{id}", id).contentType(MediaType.APPLICATION_JSON).content(actor),
				delete(base + "/activities/{id}", id).param("actorId", actorId),
				get(base + "/weeks/{week}", "2026-11-09").param("actorId", actorId),
				put(base + "/weeks/{week}", "2026-11-09").contentType(MediaType.APPLICATION_JSON).content(actor),
				post(base + "/weeks/{week}/submit", "2026-11-09").param("actorId", actorId));
		for (AbstractMockHttpServletRequestBuilder<?> request : requests) {
			mockMvc.perform(request).andExpect(status().isNotFound());
			mockMvc.perform(request.with(httpBasic("test", "test"))).andExpect(status().isNotFound());
		}
	}
}
