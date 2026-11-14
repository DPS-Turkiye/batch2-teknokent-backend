package com.dps.teknokent.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** The Teknokent deployment has no peer mentoring: every document endpoint answers 404, with or without credentials. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.edition=TEKNOKENT")
class PeerMentorDocumentTeknokentIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void everyDocumentEndpointIsClosed() throws Exception {
		UUID id = UUID.randomUUID();
		String actorId = id.toString();
		MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf",
				"%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1));
		List<AbstractMockHttpServletRequestBuilder<?>> requests = List.of(
				get("/api/peer-mentoring/profile/documents").param("actorId", actorId),
				multipart("/api/peer-mentoring/profile/documents").file(file)
						.param("evidenceType", "TRANSCRIPT")
						.param("visibility", "PROFILE")
						.param("actorId", actorId),
				post("/api/peer-mentoring/profile/links")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\":\"https://example.com\",\"visibility\":\"PROFILE\",\"actorId\":\"" + actorId + "\"}"),
				patch("/api/peer-mentoring/profile/documents/{id}", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"visibility\":\"PROFILE\",\"actorId\":\"" + actorId + "\"}"),
				delete("/api/peer-mentoring/profile/documents/{id}", id).param("actorId", actorId),
				get("/api/peer-mentoring/mentors/{mentorId}/documents", id).param("actorId", actorId),
				get("/api/peer-mentoring/documents/{id}/content", id).param("actorId", actorId));
		for (AbstractMockHttpServletRequestBuilder<?> request : requests) {
			mockMvc.perform(request.with(httpBasic("test", "test"))).andExpect(status().isNotFound());
		}
	}
}
