package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
import com.dps.teknokent.dto.requests.PostProgramAnnouncementRequest;
import com.dps.teknokent.dto.responses.ProgramAnnouncementResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.ProgramAnnouncementService;

@WebMvcTest(ProgramAnnouncementController.class)
@Import(SecurityConfig.class)
class ProgramAnnouncementControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProgramAnnouncementService announcementService;

	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/program-announcements")).andExpect(status().isUnauthorized());
		verifyNoInteractions(announcementService);
	}

	@Test
	void aCoordinatorPostsAndReadsAndAMemberReadsTheirs() throws Exception {
		ProgramAnnouncementResponse announcement = new ProgramAnnouncementResponse(
				UUID.randomUUID(), PROGRAM_ID, "METU Stars 2026", "Elif Demir", "Atölye bugün 16:00'da", Instant.now(), 42, 28);
		when(announcementService.post(eq(SIGNED_IN_ID), eq(PROGRAM_ID), any(PostProgramAnnouncementRequest.class)))
				.thenReturn(announcement);
		when(announcementService.coordinatorList(SIGNED_IN_ID, PROGRAM_ID)).thenReturn(List.of(announcement));
		when(announcementService.mine(SIGNED_IN_ID)).thenReturn(List.of(announcement));

		mockMvc.perform(post("/api/coordinator/programs/{id}/announcements", PROGRAM_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"body\":\"Atölye bugün 16:00'da\"}")
						.with(authentication(signedIn(UserRole.COORDINATOR))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.teamCount").value(42));
		mockMvc.perform(get("/api/coordinator/programs/{id}/announcements", PROGRAM_ID)
						.with(authentication(signedIn(UserRole.COORDINATOR))))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/program-announcements").with(authentication(signedIn(UserRole.MENTEE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].programName").value("METU Stars 2026"));
	}

	@Test
	void aBlankAnnouncementIsRejected() throws Exception {
		mockMvc.perform(post("/api/coordinator/programs/{id}/announcements", PROGRAM_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"body\":\"\"}")
						.with(authentication(signedIn(UserRole.COORDINATOR))))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(announcementService);
	}

	private static UsernamePasswordAuthenticationToken signedIn(UserRole role) {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "user@tekno.test", role, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}
}
