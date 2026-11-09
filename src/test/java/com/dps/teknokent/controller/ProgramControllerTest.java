package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.dto.responses.MemberProgramEventResponse;
import com.dps.teknokent.dto.responses.ProgramInvitationResponse;
import com.dps.teknokent.dto.responses.ProgramResponse;
import com.dps.teknokent.dto.responses.ProgramWeekResponse;
import com.dps.teknokent.service.ProgramCalendarService;
import com.dps.teknokent.service.ProgramWeekService;
import com.dps.teknokent.service.ProgramService;

@WebMvcTest(ProgramController.class)
@Import(SecurityConfig.class)
class ProgramControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProgramService programService;

	@MockitoBean
	private ProgramCalendarService programCalendarService;

	@MockitoBean
	private ProgramWeekService programWeekService;

	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/programs"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void listsProgramsForTheActorQueryParam() throws Exception {
		when(programService.list(ACTOR_ID)).thenReturn(List.of(program()));

		mockMvc.perform(get("/api/programs")
						.param("actorId", ACTOR_ID.toString())
						.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Seed 2026"));
	}

	@Test
	void givesTheCallerTheirProgramsWeek() throws Exception {
		when(programWeekService.thisWeek(ACTOR_ID, null)).thenReturn(new ProgramWeekResponse(PROGRAM_ID, "Seed 2026",
				null, null, 3, 12, null, null, false, false, true, null, List.of()));

		mockMvc.perform(get("/api/programs/mine/this-week")
						.param("actorId", ACTOR_ID.toString())
						.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.week").value(3))
				.andExpect(jsonPath("$.hasSyllabus").value(true));
	}

	@Test
	void theWeekNeedsSomeoneToAskFor() throws Exception {
		mockMvc.perform(get("/api/programs/mine/this-week").with(user("test-user")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void createsAProgram() throws Exception {
		when(programService.create(eq(ACTOR_ID), eq("Seed 2026"))).thenReturn(program());

		mockMvc.perform(post("/api/programs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Seed 2026\",\"actorId\":\"" + ACTOR_ID + "\"}")
						.with(user("test-user")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Seed 2026"));

		verify(programService).create(ACTOR_ID, "Seed 2026");
	}

	@Test
	void setsAProgramStatus() throws Exception {
		when(programService.setStatus(ACTOR_ID, PROGRAM_ID, ProgramStatus.COMPLETED)).thenReturn(program());

		mockMvc.perform(put("/api/programs/" + PROGRAM_ID + "/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"COMPLETED\",\"actorId\":\"" + ACTOR_ID + "\"}")
						.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		verify(programService).setStatus(ACTOR_ID, PROGRAM_ID, ProgramStatus.COMPLETED);
	}

	@Test
	void rejectsAStatusChangeWithoutAStatus() throws Exception {
		mockMvc.perform(put("/api/programs/" + PROGRAM_ID + "/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"actorId\":\"" + ACTOR_ID + "\"}")
						.with(user("test-user")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void listsPendingInvitations() throws Exception {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000006");
		when(programService.listInvitations(mentorId)).thenReturn(List.of(new ProgramInvitationResponse(
				PROGRAM_ID,
				"Seed 2026",
				ACTOR_ID,
				"Demo Coordinator",
				Instant.parse("2026-09-01T09:00:00Z"))));

		mockMvc.perform(get("/api/programs/invitations")
						.param("actorId", mentorId.toString())
						.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].programName").value("Seed 2026"));
	}

	@Test
	void listsTheCallersProgramEventsInARange() throws Exception {
		Instant from = Instant.parse("2026-09-21T00:00:00Z");
		Instant to = Instant.parse("2026-09-28T00:00:00Z");
		when(programCalendarService.eventsFor(ACTOR_ID, from, to)).thenReturn(List.of(
				new MemberProgramEventResponse(UUID.randomUUID(), PROGRAM_ID, "Seed 2026", "Ara rapor teslimi",
						ProgramEventKind.DEADLINE, Instant.parse("2026-09-25T20:59:00Z"), null, null)));

		mockMvc.perform(get("/api/programs/events")
						.param("from", from.toString())
						.param("to", to.toString())
						.param("actorId", ACTOR_ID.toString())
						.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].programName").value("Seed 2026"))
				.andExpect(jsonPath("$[0].kind").value("DEADLINE"));
	}

	@Test
	void requiresAnActorForProgramEvents() throws Exception {
		mockMvc.perform(get("/api/programs/events")
						.param("from", "2026-09-21T00:00:00Z")
						.param("to", "2026-09-28T00:00:00Z")
						.with(user("test-user")))
				.andExpect(status().isBadRequest());
	}

	private static ProgramResponse program() {
		return new ProgramResponse(
				PROGRAM_ID,
				"Seed 2026",
				ACTOR_ID,
				"Demo Coordinator",
				Instant.parse("2026-09-01T08:00:00Z"),
				ProgramStatus.ACTIVE,
				List.of(),
				List.of());
	}
}
