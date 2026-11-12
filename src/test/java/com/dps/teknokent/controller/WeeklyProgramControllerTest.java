package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.dps.teknokent.domain.WeeklyProgramWarning;
import com.dps.teknokent.dto.requests.ReadWeeklyProgramRequest;
import com.dps.teknokent.dto.requests.SaveWeeklyProgramRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.dto.responses.WeeklyProgramProposalResponse;
import com.dps.teknokent.dto.responses.WeeklyProgramSavedResponse;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.WeeklyProgramService;

@WebMvcTest(WeeklyProgramController.class)
@Import(SecurityConfig.class)
class WeeklyProgramControllerTest {

	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private WeeklyProgramService weeklyProgramService;

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/coordinator/programs/{id}/weekly-program/read", PROGRAM_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"documentId\":\"" + DOCUMENT_ID + "\"}"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(weeklyProgramService);
	}

	@Test
	void readsAsTheSignedInUserNotTheActorInTheBody() throws Exception {
		LocalDate start = LocalDate.of(2026, 10, 27);
		when(weeklyProgramService.read(eq(SIGNED_IN_ID), eq(PROGRAM_ID), any(ReadWeeklyProgramRequest.class)))
				.thenReturn(new WeeklyProgramProposalResponse(11,
						List.of(new ProgramWeekPlanResponse.Week(1, start, start.plusDays(6), "Yola Çık", List.of(),
								List.of(), List.of(), false)),
						List.of(new WeeklyProgramProposalResponse.Phase("Problemi Keşfet", null, 1, 3, start,
								start.plusDays(20))),
						List.of(new WeeklyProgramProposalResponse.Warning(WeeklyProgramWarning.DATES_DIFFER,
								List.of(8))),
						List.of("Demo Day"), List.of(), 1, new WeeklyProgramProposalResponse.Existing(0, 0, null)));

		mockMvc.perform(post("/api/coordinator/programs/{id}/weekly-program/read", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"actorId\":\"" + ACTOR_ID + "\",\"documentId\":\"" + DOCUMENT_ID + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalWeeks").value(11))
				.andExpect(jsonPath("$.weeks[0].startDate").value("2026-10-27"))
				.andExpect(jsonPath("$.phases[0].firstWeek").value(1))
				.andExpect(jsonPath("$.phases[0].lastWeek").value(3))
				.andExpect(jsonPath("$.warnings[0].code").value("DATES_DIFFER"))
				.andExpect(jsonPath("$.warnings[0].weeks[0]").value(8))
				.andExpect(jsonPath("$.notes[0]").value("Demo Day"))
				.andExpect(jsonPath("$.existing.currentWeek").doesNotExist());
	}

	@Test
	void aReadNeedsADocument() throws Exception {
		mockMvc.perform(post("/api/coordinator/programs/{id}/weekly-program/read", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(weeklyProgramService);
	}

	@Test
	void savesAsTheSignedInUser() throws Exception {
		when(weeklyProgramService.save(eq(SIGNED_IN_ID), eq(PROGRAM_ID), any(SaveWeeklyProgramRequest.class)))
				.thenReturn(new WeeklyProgramSavedResponse(null, new ProgramWeekPlanResponse(11, List.of())));

		mockMvc.perform(put("/api/coordinator/programs/{id}/weekly-program", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(saveBody("Problemi Keşfet", 1, 3)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.plan.totalWeeks").value(11));
	}

	@Test
	void aSaveWithAnInvalidPhaseIsRefusedBeforeTheService() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}/weekly-program", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(saveBody(" ", 0, 3)))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(weeklyProgramService);
	}

	@Test
	void aSaveWithoutWeeksIsRefusedSoItCannotWipeThePlan() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}/weekly-program", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"documentId": "%s", "weeks": [],
								 "phases": [{"name": "A", "expectedOutput": null, "firstWeek": 1, "lastWeek": 3}],
								 "seenPhaseCount": 0, "seenWeekCount": 0, "keepPast": true}
								""".formatted(DOCUMENT_ID)))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(weeklyProgramService);
	}

	@Test
	void aPlanThatChangedSinceTheReadIsAConflict() throws Exception {
		when(weeklyProgramService.save(eq(SIGNED_IN_ID), eq(PROGRAM_ID), any(SaveWeeklyProgramRequest.class)))
				.thenThrow(new ConflictException("The program's plan changed since the file was read; read it again."));

		mockMvc.perform(put("/api/coordinator/programs/{id}/weekly-program", PROGRAM_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(saveBody("Problemi Keşfet", 1, 3)))
				.andExpect(status().isConflict());
	}

	private static String saveBody(String phaseName, int firstWeek, int lastWeek) {
		return """
				{"documentId": "%s",
				 "weeks": [{"week": 1, "title": "Yola Çık", "topics": [], "activities": [], "deliverables": [],
				            "inferred": false}],
				 "phases": [{"name": "%s", "expectedOutput": null, "firstWeek": %d, "lastWeek": %d}],
				 "seenPhaseCount": 0, "seenWeekCount": 0, "keepPast": true}
				""".formatted(DOCUMENT_ID, phaseName, firstWeek, lastWeek);
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "coordinator@example.test", UserRole.COORDINATOR,
						UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}
}
