package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.dps.teknokent.dto.requests.AddCoordinatorNoteRequest;
import com.dps.teknokent.dto.responses.CoordinatorNoteResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.CoordinatorNoteService;

@WebMvcTest(CoordinatorNoteController.class)
@Import(SecurityConfig.class)
class CoordinatorNoteControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CoordinatorNoteService noteService;

	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID MENTORSHIP_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID NOTE_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/coordinator/mentorships/{id}/notes", MENTORSHIP_ID)).andExpect(status().isUnauthorized());
		verifyNoInteractions(noteService);
	}

	@Test
	void listsAddsAndDeletesAsTheSignedInCoordinator() throws Exception {
		CoordinatorNoteResponse note = new CoordinatorNoteResponse(
				NOTE_ID, MENTORSHIP_ID, "Elif Demir", "Nakit akışı riskli", Instant.now(), true);
		when(noteService.list(SIGNED_IN_ID, MENTORSHIP_ID)).thenReturn(List.of(note));
		when(noteService.add(eq(SIGNED_IN_ID), eq(MENTORSHIP_ID), any(AddCoordinatorNoteRequest.class))).thenReturn(note);

		mockMvc.perform(get("/api/coordinator/mentorships/{id}/notes", MENTORSHIP_ID).with(authentication(signedIn())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].authorName").value("Elif Demir"));
		mockMvc.perform(post("/api/coordinator/mentorships/{id}/notes", MENTORSHIP_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"body\":\"Nakit akışı riskli\"}")
						.with(authentication(signedIn())))
				.andExpect(status().isCreated());
		mockMvc.perform(delete("/api/coordinator/notes/{id}", NOTE_ID).with(authentication(signedIn())))
				.andExpect(status().isNoContent());

		verify(noteService).delete(SIGNED_IN_ID, NOTE_ID);
	}

	@Test
	void aBlankNoteIsRejected() throws Exception {
		mockMvc.perform(post("/api/coordinator/mentorships/{id}/notes", MENTORSHIP_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"body\":\"   \"}")
						.with(authentication(signedIn())))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(noteService);
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "elif@tekno.test", UserRole.COORDINATOR, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}
}
