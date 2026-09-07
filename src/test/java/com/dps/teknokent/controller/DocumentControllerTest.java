package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.responses.StoredDocumentResponse;
import com.dps.teknokent.service.DocumentService;

@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
class DocumentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DocumentService documentService;

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(multipart("/api/documents")
						.file(pdf())
						.param("kind", "cv"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void storesADocumentForTheRequestedUserWhenUsingMachineCredentials() throws Exception {
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000811");
		UUID documentId = UUID.fromString("00000000-0000-0000-0000-000000000912");
		when(documentService.storeForUser(eq(userId), eq("cv"), any())).thenReturn(new StoredDocumentResponse(
				documentId,
				userId,
				null,
				"cv",
				"cv.pdf",
				"application/pdf",
				12,
				Instant.parse("2026-08-28T12:00:00Z")));

		mockMvc.perform(multipart("/api/documents")
						.file(pdf())
						.param("kind", "cv")
						.param("userId", userId.toString())
						.with(user("test-user")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(documentId.toString()))
				.andExpect(jsonPath("$.kind").value("cv"));

		verify(documentService).storeForUser(eq(userId), eq("cv"), any());
	}

	@Test
	void requiresAuthenticationToList() throws Exception {
		mockMvc.perform(get("/api/documents"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void listsDocumentsForTheRequestedUserWhenUsingMachineCredentials() throws Exception {
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000811");
		UUID documentId = UUID.fromString("00000000-0000-0000-0000-000000000912");
		when(documentService.listForUser(userId)).thenReturn(List.of(new StoredDocumentResponse(
				documentId,
				userId,
				null,
				"cv",
				"cv.pdf",
				"application/pdf",
				12,
				Instant.parse("2026-08-28T12:00:00Z"))));

		mockMvc.perform(get("/api/documents")
						.param("userId", userId.toString())
						.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(documentId.toString()))
				.andExpect(jsonPath("$[0].kind").value("cv"));

		verify(documentService).listForUser(userId);
	}

	private static MockMultipartFile pdf() {
		return new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF-1.4".getBytes());
	}
}
