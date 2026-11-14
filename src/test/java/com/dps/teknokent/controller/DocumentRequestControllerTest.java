package com.dps.teknokent.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AttachDocumentRequestUploadRequest;
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import com.dps.teknokent.dto.responses.DocumentRequestUploadResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.DocumentRequestService;

@WebMvcTest(DocumentRequestController.class)
@Import(SecurityConfig.class)
class DocumentRequestControllerTest {

	private static final UUID PROGRAM_ID = UUID.fromString("90000000-0000-0000-0000-000000000001");
	private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000009");
	private static final UUID ITEM_ID = UUID.fromString("80000000-0000-0000-0000-000000000001");
	private static final UUID SIGNED_IN_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
	private static final UUID REQUEST_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
	private static final UUID MEETING_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
	private static final UUID MENTORSHIP_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
	private static final UUID DOCUMENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
	private static final UUID UPLOAD_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DocumentRequestService documentRequestService;

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(put("/api/document-requests")
				.param("programId", PROGRAM_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\", \"titles\": [\"CV\"]}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void replacesAProgramsDocumentRequestsWithADueDate() throws Exception {
		LocalDate dueDate = LocalDate.parse("2026-09-30");
		when(documentRequestService.replaceOnProgram(PROGRAM_ID, ACTOR_ID, List.of("Finansal tablolar"), dueDate))
				.thenReturn(List.of(new DocumentRequestItemResponse(ITEM_ID, "Finansal tablolar", 0, dueDate, ORG_ID,
						null, null, null, PROGRAM_ID, ACTOR_ID, "Ece Koordinatör",
						Instant.parse("2026-09-14T08:00:00Z"), List.of())));

		mockMvc.perform(put("/api/document-requests")
				.param("programId", PROGRAM_ID.toString())
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "actorId": "20000000-0000-0000-0000-000000000009",
						  "titles": ["Finansal tablolar"],
						  "dueDate": "2026-09-30"
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].programId").value(PROGRAM_ID.toString()))
				.andExpect(jsonPath("$[0].dueDate").value("2026-09-30"))
				.andExpect(jsonPath("$[0].createdByName").value("Ece Koordinatör"));
	}

	@Test
	void rejectsAReplaceWithoutATarget() throws Exception {
		mockMvc.perform(put("/api/document-requests")
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\", \"titles\": [\"CV\"]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void requiresAuthenticationToList() throws Exception {
		mockMvc.perform(get("/api/document-requests").param("meetingId", MEETING_ID.toString()))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(documentRequestService);
	}

	@Test
	void listsTheChecklistOfAMeetingWithItsUploads() throws Exception {
		when(documentRequestService.list(null, MEETING_ID, null)).thenReturn(List.of(
				new DocumentRequestItemResponse(ITEM_ID, "Kullanıcı akışı", 0, null, ORG_ID, null, MEETING_ID, null,
						null, ACTOR_ID, "Nihan Öztürk", Instant.parse("2026-09-14T08:00:00Z"),
						List.of(upload()))));

		mockMvc.perform(get("/api/document-requests")
				.param("meetingId", MEETING_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(ITEM_ID.toString()))
				.andExpect(jsonPath("$[0].meetingId").value(MEETING_ID.toString()))
				.andExpect(jsonPath("$[0].uploads[0].storedDocumentId").value(DOCUMENT_ID.toString()))
				.andExpect(jsonPath("$[0].uploads[0].originalFilename").value("kullanici-akisi.pdf"));
	}

	@Test
	void listsTheChecklistOfAMentorshipRequestOrAMentorship() throws Exception {
		mockMvc.perform(get("/api/document-requests")
				.param("mentorshipRequestId", REQUEST_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/document-requests")
				.param("mentorshipId", MENTORSHIP_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk());

		verify(documentRequestService).list(REQUEST_ID, null, null);
		verify(documentRequestService).list(null, null, MENTORSHIP_ID);
	}

	@Test
	void replacesAMentorshipRequestsChecklistAsTheSignedInUserWhateverActorIdIsSent() throws Exception {
		LocalDate dueDate = LocalDate.parse("2026-09-30");
		when(documentRequestService.replaceOnRequest(REQUEST_ID, SIGNED_IN_ID, List.of("Pitch deck"), dueDate))
				.thenReturn(List.of(new DocumentRequestItemResponse(ITEM_ID, "Pitch deck", 0, dueDate, ORG_ID,
						REQUEST_ID, null, null, null, SIGNED_IN_ID, "Nihan Öztürk",
						Instant.parse("2026-09-14T08:00:00Z"), List.of())));

		mockMvc.perform(put("/api/document-requests")
				.param("mentorshipRequestId", REQUEST_ID.toString())
				.with(authentication(signedInAs(SIGNED_IN_ID)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "actorId": "%s", "titles": ["Pitch deck"], "dueDate": "2026-09-30" }
						""".formatted(ACTOR_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].mentorshipRequestId").value(REQUEST_ID.toString()))
				.andExpect(jsonPath("$[0].createdById").value(SIGNED_IN_ID.toString()));
	}

	@Test
	void replacesAMeetingsChecklist() throws Exception {
		mockMvc.perform(put("/api/document-requests")
				.param("meetingId", MEETING_ID.toString())
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\", \"titles\": [\"Kullanıcı akışı\"]}"))
				.andExpect(status().isOk());

		verify(documentRequestService).replaceOnMeeting(MEETING_ID, ACTOR_ID, List.of("Kullanıcı akışı"), null);
	}

	@Test
	void replacesAMentorshipsChecklist() throws Exception {
		mockMvc.perform(put("/api/document-requests")
				.param("mentorshipId", MENTORSHIP_ID.toString())
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\", \"titles\": [], \"dueDate\": \"2026-10-15\"}"))
				.andExpect(status().isOk());

		verify(documentRequestService).replaceOnMentorship(
				MENTORSHIP_ID, ACTOR_ID, List.of(), LocalDate.parse("2026-10-15"));
	}

	@Test
	void rejectsAReplaceWithABlankOrOverlongTitle() throws Exception {
		for (String title : List.of("   ", "a".repeat(501))) {
			mockMvc.perform(put("/api/document-requests")
					.param("programId", PROGRAM_ID.toString())
					.with(user("test-user"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"actorId\": \"" + ACTOR_ID + "\", \"titles\": [\"CV\", \"" + title + "\"]}"))
					.andExpect(status().isBadRequest());
		}

		verifyNoInteractions(documentRequestService);
	}

	@Test
	void rejectsAReplaceThatNamesNoActor() throws Exception {
		mockMvc.perform(put("/api/document-requests")
				.param("programId", PROGRAM_ID.toString())
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"titles\": [\"CV\"]}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(documentRequestService);
	}

	@Test
	void requiresAuthenticationToAttachAnUpload() throws Exception {
		mockMvc.perform(post("/api/document-requests/{itemId}/uploads", ITEM_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\", \"storedDocumentId\": \"" + DOCUMENT_ID + "\"}"))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(documentRequestService);
	}

	@Test
	void attachesAnUploadedFileToAChecklistItem() throws Exception {
		when(documentRequestService.attachUpload(ITEM_ID,
				new AttachDocumentRequestUploadRequest(ACTOR_ID, DOCUMENT_ID))).thenReturn(upload());

		mockMvc.perform(post("/api/document-requests/{itemId}/uploads", ITEM_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\", \"storedDocumentId\": \"" + DOCUMENT_ID + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(UPLOAD_ID.toString()))
				.andExpect(jsonPath("$.storedDocumentId").value(DOCUMENT_ID.toString()))
				.andExpect(jsonPath("$.originalFilename").value("kullanici-akisi.pdf"))
				.andExpect(jsonPath("$.sizeBytes").value(2048))
				.andExpect(jsonPath("$.uploadedByName").value("Gülse Buran"));
	}

	@Test
	void rejectsAnAttachThatNamesNoFileOrNoActor() throws Exception {
		for (String body : List.of(
				"{\"actorId\": \"" + ACTOR_ID + "\"}",
				"{\"storedDocumentId\": \"" + DOCUMENT_ID + "\"}")) {
			mockMvc.perform(post("/api/document-requests/{itemId}/uploads", ITEM_ID)
					.with(user("test-user"))
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isBadRequest());
		}

		verifyNoInteractions(documentRequestService);
	}

	@Test
	void requiresAuthenticationToRemoveAnUpload() throws Exception {
		mockMvc.perform(delete("/api/document-requests/uploads/{uploadId}", UPLOAD_ID)
				.param("actorId", ACTOR_ID.toString()))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(documentRequestService);
	}

	@Test
	void removesAnUpload() throws Exception {
		mockMvc.perform(delete("/api/document-requests/uploads/{uploadId}", UPLOAD_ID)
				.param("actorId", ACTOR_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isNoContent());

		verify(documentRequestService).deleteUpload(UPLOAD_ID, ACTOR_ID);
	}

	@Test
	void rejectsRemovingAnUploadWithoutAnActor() throws Exception {
		mockMvc.perform(delete("/api/document-requests/uploads/{uploadId}", UPLOAD_ID).with(user("test-user")))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(documentRequestService);
	}

	@Test
	void attachesAnUploadAsTheSignedInUserWhateverActorIdIsSent() throws Exception {
		when(documentRequestService.attachUpload(ITEM_ID,
				new AttachDocumentRequestUploadRequest(SIGNED_IN_ID, DOCUMENT_ID))).thenReturn(upload());

		mockMvc.perform(post("/api/document-requests/{itemId}/uploads", ITEM_ID)
				.with(authentication(signedInAs(SIGNED_IN_ID)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\", \"storedDocumentId\": \"" + DOCUMENT_ID + "\"}"))
				.andExpect(status().isCreated());

		verify(documentRequestService).attachUpload(ITEM_ID,
				new AttachDocumentRequestUploadRequest(SIGNED_IN_ID, DOCUMENT_ID));
	}

	@Test
	void removesAnUploadAsTheSignedInUserWhateverActorIdIsSent() throws Exception {
		mockMvc.perform(delete("/api/document-requests/uploads/{uploadId}", UPLOAD_ID)
				.param("actorId", ACTOR_ID.toString())
				.with(authentication(signedInAs(SIGNED_IN_ID))))
				.andExpect(status().isNoContent());

		verify(documentRequestService).deleteUpload(UPLOAD_ID, SIGNED_IN_ID);
	}

	private static DocumentRequestUploadResponse upload() {
		return new DocumentRequestUploadResponse(UPLOAD_ID, DOCUMENT_ID, "kullanici-akisi.pdf", "application/pdf",
				2048, ACTOR_ID, "Gülse Buran", Instant.parse("2026-09-15T09:30:00Z"));
	}

	private static UsernamePasswordAuthenticationToken signedInAs(UUID userId) {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(userId, "nihan@mentor.test", UserRole.MENTOR, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}
}
