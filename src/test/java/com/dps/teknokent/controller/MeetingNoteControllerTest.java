package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.requests.UpdateMeetingNoteRequest;
import com.dps.teknokent.dto.responses.MeetingNoteDocumentResponse;
import com.dps.teknokent.dto.responses.MeetingNoteResponse;
import com.dps.teknokent.service.MeetingAssistantShareService;
import com.dps.teknokent.service.MeetingNoteDocumentService;
import com.dps.teknokent.service.MeetingNoteService;

@WebMvcTest(MeetingNoteController.class)
@Import(SecurityConfig.class)
class MeetingNoteControllerTest {

	private static final UUID MEETING_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

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
		when(meetingNoteService.findNote(MEETING_ID, ORGANIZATION_ID)).thenReturn(new MeetingNoteResponse(
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
		when(meetingNoteService.updateNote(eq(MEETING_ID), any(UpdateMeetingNoteRequest.class)))
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
		when(meetingNoteDocumentService.findDocument(MEETING_ID, ORGANIZATION_ID))
				.thenReturn(new MeetingNoteDocumentResponse("AQID", false));

		mockMvc.perform(get("/api/meetings/{meetingId}/note/document", MEETING_ID)
				.param("organizationId", ORGANIZATION_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("AQID"))
				.andExpect(jsonPath("$.seedFromSnapshot").value(false));
	}
}
