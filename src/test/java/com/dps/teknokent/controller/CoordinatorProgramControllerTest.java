package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Collections;
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
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.ProgramLifecycle;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProgramEventRequest;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.dto.responses.ProgramEventResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.CoordinatorProgramService;
import com.dps.teknokent.service.ProgramCoordinatorService;

@WebMvcTest(CoordinatorProgramController.class)
@Import(SecurityConfig.class)
class CoordinatorProgramControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CoordinatorProgramService coordinatorProgramService;
	@MockitoBean
	private ProgramCoordinatorService programCoordinatorService;

	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/coordinator/programs"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(coordinatorProgramService);
	}

	@Test
	void listsForTheActorOnTheServiceCredential() throws Exception {
		when(coordinatorProgramService.list(ACTOR_ID)).thenReturn(List.of(summary()));

		mockMvc.perform(get("/api/coordinator/programs")
						.param("actorId", ACTOR_ID.toString())
						.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("METU Stars 2026"))
				.andExpect(jsonPath("$[0].lifecycle").value("ACTIVE"));
	}

	@Test
	void actsAsTheSignedInUserWhateverActorIdIsSent() throws Exception {
		when(coordinatorProgramService.list(SIGNED_IN_ID)).thenReturn(List.of());

		mockMvc.perform(get("/api/coordinator/programs")
						.param("actorId", ACTOR_ID.toString())
						.with(authentication(signedIn())))
				.andExpect(status().isOk());

		verify(coordinatorProgramService).list(SIGNED_IN_ID);
	}

	@Test
	void rejectsAProgramUpdateWithoutAName() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\" \"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(coordinatorProgramService);
	}

	@Test
	void rejectsMoreThanTwelvePhases() throws Exception {
		String phases = String.join(",", Collections.nCopies(13, "{\"name\":\"Faz\"}"));

		mockMvc.perform(put("/api/coordinator/programs/{id}/phases", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phases\":[" + phases + "]}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(coordinatorProgramService);
	}

	@Test
	void createsAnEvent() throws Exception {
		Instant startsAt = Instant.parse("2026-10-17T20:59:00Z");
		when(coordinatorProgramService.createEvent(eq(SIGNED_IN_ID), eq(PROGRAM_ID), any(CreateProgramEventRequest.class)))
				.thenReturn(new ProgramEventResponse(UUID.randomUUID(), PROGRAM_ID, "Ara Rapor Teslimi",
						ProgramEventKind.DEADLINE, startsAt, null, null));

		mockMvc.perform(post("/api/coordinator/programs/{id}/events", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "title": "Ara Rapor Teslimi", "kind": "DEADLINE", "startsAt": "2026-10-17T20:59:00Z" }
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("DEADLINE"));
	}

	@Test
	void deletesAnEvent() throws Exception {
		UUID eventId = UUID.randomUUID();

		mockMvc.perform(delete("/api/coordinator/programs/{id}/events/{eventId}", PROGRAM_ID, eventId)
						.with(authentication(signedIn())))
				.andExpect(status().isNoContent());

		verify(coordinatorProgramService).deleteEvent(SIGNED_IN_ID, PROGRAM_ID, eventId);
	}

	@Test
	void archivesAndManagesCoordinatorsAsTheSignedInUser() throws Exception {
		UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
		when(programCoordinatorService.add(SIGNED_IN_ID, PROGRAM_ID, other)).thenReturn(List.of(
				new CoordinatorProgramResponse.CoordinatorRef(SIGNED_IN_ID, "Elif Demir", true),
				new CoordinatorProgramResponse.CoordinatorRef(other, "Burcu Şen", false)));

		mockMvc.perform(put("/api/coordinator/programs/{id}/archive", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"archived\":true}"))
				.andExpect(status().isOk());
		verify(coordinatorProgramService).setArchived(SIGNED_IN_ID, PROGRAM_ID, true);

		mockMvc.perform(post("/api/coordinator/programs/{id}/coordinators", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"" + other + "\",\"actorId\":\"" + ACTOR_ID + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[1].fullName").value("Burcu Şen"))
				.andExpect(jsonPath("$[0].owner").value(true));

		mockMvc.perform(delete("/api/coordinator/programs/{id}/coordinators/{userId}", PROGRAM_ID, other)
						.with(authentication(signedIn())))
				.andExpect(status().isOk());
		verify(programCoordinatorService).remove(SIGNED_IN_ID, PROGRAM_ID, other);
	}

	@Test
	void archivingNeedsAnAnswer() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}/archive", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(coordinatorProgramService);
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "elif@tekno.test", UserRole.COORDINATOR, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}

	private static CoordinatorProgramResponse summary() {
		return new CoordinatorProgramResponse(
				PROGRAM_ID, "METU Stars 2026", ProgramStatus.ACTIVE, ProgramLifecycle.ACTIVE, null, null, null,
				ACTOR_ID, "Elif Demir", 42, 28, 3, 64, 6, 5, 4, null, List.of(), null, null);
	}
}
