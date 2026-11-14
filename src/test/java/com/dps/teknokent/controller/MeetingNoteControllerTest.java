package com.dps.teknokent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.MeetingAssistantShareKind;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.PublishMeetingAssistantShareRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingNoteRequest;
import com.dps.teknokent.dto.responses.MeetingAssistantShareResponse;
import com.dps.teknokent.dto.responses.MeetingNoteDocumentResponse;
import com.dps.teknokent.dto.responses.MeetingNoteResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.MeetingAssistantShareService;
import com.dps.teknokent.service.MeetingNoteDocumentService;
import com.dps.teknokent.service.MeetingNoteService;
import com.dps.teknokent.util.JsonUtils;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(MeetingNoteController.class)
@Import(SecurityConfig.class)
class MeetingNoteControllerTest {

	private static final UUID MEETING_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private static final UUID CALLER = UUID.fromString("00000000-0000-0000-0000-000000000209");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MeetingNoteService meetingNoteService;

	@MockitoBean
	private MeetingNoteDocumentService meetingNoteDocumentService;

	@MockitoBean
	private MeetingAssistantShareService meetingAssistantShareService;

	@Test
	void requiresAuthenticationForNotes() throws Exception {
		mockMvc.perform(get("/api/meetings/{meetingId}/note", MEETING_ID)
				.param("organizationId", ORGANIZATION_ID.toString()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void returnsTheNoteWithItsVersion() throws Exception {
		when(meetingNoteService.findNote(MEETING_ID, ORGANIZATION_ID, null)).thenReturn(new MeetingNoteResponse(
				MEETING_ID,
				"<p>Sprint notlari</p>",
				7L,
				USER_ID,
				"Elif Demir",
				Instant.parse("2026-09-15T16:00:00Z")));

		mockMvc.perform(get("/api/meetings/{meetingId}/note", MEETING_ID)
				.param("organizationId", ORGANIZATION_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(7))
				.andExpect(jsonPath("$.updatedByName").value("Elif Demir"));
	}

	@Test
	void storesTheSnapshotSentByTheEditor() throws Exception {
		when(meetingNoteService.updateNote(eq(MEETING_ID), eq(USER_ID), any(UpdateMeetingNoteRequest.class)))
				.thenReturn(new MeetingNoteResponse(
						MEETING_ID,
						"<p>Yeni not</p>",
						8L,
						USER_ID,
						"Elif Demir",
						Instant.parse("2026-09-15T16:05:00Z")));

		mockMvc.perform(put("/api/meetings/{meetingId}/note", MEETING_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "organizationId": "00000000-0000-0000-0000-000000000101",
						  "userId": "00000000-0000-0000-0000-000000000201",
						  "content": "<p>Yeni not</p>"
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(8));
	}

	@Test
	void rejectsSaveRequestsWithoutContent() throws Exception {
		mockMvc.perform(put("/api/meetings/{meetingId}/note", MEETING_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "organizationId": "00000000-0000-0000-0000-000000000101",
						  "userId": "00000000-0000-0000-0000-000000000201"
						}
						"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returnsTheLiveDocumentStateForTheRoom() throws Exception {
		when(meetingNoteDocumentService.findDocument(MEETING_ID, ORGANIZATION_ID, null))
				.thenReturn(new MeetingNoteDocumentResponse("AQID", false));

		mockMvc.perform(get("/api/meetings/{meetingId}/note/document", MEETING_ID)
				.param("organizationId", ORGANIZATION_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("AQID"))
				.andExpect(jsonPath("$.seedFromSnapshot").value(false));
	}

	@Test
	void returnsAPublishedTranscriptAsJson() throws Exception {
		when(meetingAssistantShareService.find(MEETING_ID, ORGANIZATION_ID, null)).thenReturn(
				new MeetingAssistantShareResponse(MEETING_ID, transcript(), null, Instant.parse("2026-09-15T17:00:00Z"),
						null, null, null));

		mockMvc.perform(get("/api/meetings/{meetingId}/assistant-share", MEETING_ID)
				.param("organizationId", ORGANIZATION_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transcript[0].speaker").value("Elif"))
				.andExpect(jsonPath("$.transcript[0].text").value("Merhaba"));
	}

	@Test
	void readsTheTranscriptOfAPublishRequest() throws Exception {
		ArgumentCaptor<PublishMeetingAssistantShareRequest> request = ArgumentCaptor
				.forClass(PublishMeetingAssistantShareRequest.class);
		when(meetingAssistantShareService.publish(eq(MEETING_ID), request.capture()))
				.thenReturn(MeetingAssistantShareResponse.empty(MEETING_ID));

		mockMvc.perform(post("/api/meetings/{meetingId}/assistant-share", MEETING_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"actorId":"%s","kind":"TRANSCRIPT","transcript":[{"speaker":"Elif","text":"Merhaba"}]}
						""".formatted(USER_ID)))
				.andExpect(status().isOk());

		assertThat(request.getValue().transcript().get(0).get("text").asString()).isEqualTo("Merhaba");
		assertThat(request.getValue().kind()).isEqualTo(MeetingAssistantShareKind.TRANSCRIPT);
		assertThat(request.getValue().actorId()).isEqualTo(USER_ID);
	}

	@Test
	void publishesAsTheBearerCallerNotTheActorInTheBody() throws Exception {
		ArgumentCaptor<PublishMeetingAssistantShareRequest> request = ArgumentCaptor
				.forClass(PublishMeetingAssistantShareRequest.class);
		when(meetingAssistantShareService.publish(eq(MEETING_ID), request.capture()))
				.thenReturn(MeetingAssistantShareResponse.empty(MEETING_ID));

		mockMvc.perform(post("/api/meetings/{meetingId}/assistant-share", MEETING_ID)
				.with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"actorId":"%s","kind":"REPORT","report":{"title":"Rapor"}}
						""".formatted(UUID.randomUUID())))
				.andExpect(status().isOk());

		assertThat(request.getValue().actorId()).isEqualTo(CALLER);
	}

	@Test
	void rejectsAKindTheNotebookDoesNotKnow() throws Exception {
		mockMvc.perform(post("/api/meetings/{meetingId}/assistant-share", MEETING_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"actorId":"%s","kind":"SUMMARY","report":{"title":"Rapor"}}
						""".formatted(USER_ID)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aBearerCallerReadsNotesTodosAndTheNotebookAsThemselves() throws Exception {
		UUID somebodyElse = UUID.randomUUID();

		mockMvc.perform(get("/api/meetings/{meetingId}/note", MEETING_ID)
				.param("organizationId", ORGANIZATION_ID.toString())
				.param("actorId", somebodyElse.toString())
				.with(signedIn()))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/meetings/{meetingId}/todos", MEETING_ID)
				.param("organizationId", ORGANIZATION_ID.toString())
				.with(signedIn()))
				.andExpect(status().isOk());
		mockMvc.perform(delete("/api/meetings/{meetingId}/todos/{todoId}", MEETING_ID, UUID.randomUUID())
				.param("organizationId", ORGANIZATION_ID.toString())
				.with(signedIn()))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/meetings/{meetingId}/assistant-share", MEETING_ID)
				.param("organizationId", ORGANIZATION_ID.toString())
				.param("viewerId", somebodyElse.toString())
				.with(signedIn()))
				.andExpect(status().isOk());

		verify(meetingNoteService).findNote(MEETING_ID, ORGANIZATION_ID, CALLER);
		verify(meetingNoteService).findTodos(MEETING_ID, ORGANIZATION_ID, CALLER);
		verify(meetingNoteService).deleteTodo(eq(MEETING_ID), any(), eq(ORGANIZATION_ID), eq(CALLER));
		verify(meetingAssistantShareService).find(MEETING_ID, ORGANIZATION_ID, CALLER);
	}

	@Test
	void writesTheNoteAndAddsTodosAsTheBearerCaller() throws Exception {
		mockMvc.perform(put("/api/meetings/{meetingId}/note", MEETING_ID)
				.with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"organizationId":"%s","userId":"%s","content":"<p>Not</p>"}
						""".formatted(ORGANIZATION_ID, USER_ID)))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/meetings/{meetingId}/todos", MEETING_ID)
				.with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"organizationId":"%s","userId":"%s","title":"Görev"}
						""".formatted(ORGANIZATION_ID, USER_ID)))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/meetings/{meetingId}/assistant-share/transcript-upload", MEETING_ID)
				.with(signedIn())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isOk());

		verify(meetingNoteService).updateNote(eq(MEETING_ID), eq(CALLER), any(UpdateMeetingNoteRequest.class));
		verify(meetingNoteService).createTodo(eq(MEETING_ID), eq(CALLER), any());
		verify(meetingAssistantShareService).createTranscriptUpload(MEETING_ID, CALLER);
	}

	@Test
	void anUploadLinkOnServiceCredentialsNeedsTheActor() throws Exception {
		mockMvc.perform(post("/api/meetings/{meetingId}/assistant-share/report-upload", MEETING_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest());
	}

	private static RequestPostProcessor signedIn() {
		return authentication(new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				CALLER, "caller@test.dev", UserRole.MENTEE, UserAccountStatus.APPROVED, "caller-uid"), null,
				List.of()));
	}

	private static tools.jackson.databind.JsonNode transcript() {
		return new JsonUtils(new ObjectMapper()).readTree("[{\"speaker\":\"Elif\",\"text\":\"Merhaba\"}]");
	}
}
