package com.dps.teknokent.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
import com.dps.teknokent.domain.WeekVisibility;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.ProgramWeekPlanService;

@WebMvcTest(ProgramWeekPlanController.class)
@Import(SecurityConfig.class)
class ProgramWeekPlanControllerTest {

	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProgramWeekPlanService programWeekPlanService;

	@Test
	void settingAWeeksVisibilityRequiresAuthentication() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}/weeks/{week}/visibility", PROGRAM_ID, 3)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"SHOWN\"}"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(programWeekPlanService);
	}

	@Test
	void setsAWeeksVisibilityAsTheSignedInUserNotTheActorInTheBody() throws Exception {
		LocalDate start = LocalDate.of(2026, 10, 19);
		when(programWeekPlanService.setVisibility(SIGNED_IN_ID, PROGRAM_ID, 3, WeekVisibility.SHOWN))
				.thenReturn(new ProgramWeekPlanResponse.Week(3, start, start.plusDays(6), false, "Prototip", List.of(),
						List.of(), List.of(), false, List.of(), WeekVisibility.SHOWN));

		mockMvc.perform(put("/api/coordinator/programs/{id}/weeks/{week}/visibility", PROGRAM_ID, 3)
				.with(authentication(signedIn()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + ACTOR_ID + "\",\"visibility\":\"SHOWN\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.week").value(3))
				.andExpect(jsonPath("$.menteeVisibility").value("SHOWN"));
	}

	@Test
	void aMissingOrUnknownVisibilityIsRefused() throws Exception {
		for (String body : List.of("{}", "{\"visibility\":\"EVERYONE\"}")) {
			mockMvc.perform(put("/api/coordinator/programs/{id}/weeks/{week}/visibility", PROGRAM_ID, 3)
					.with(authentication(signedIn()))
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(programWeekPlanService);
	}

	@Test
	void aWeekThatIsNotANumberIsRefused() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}/weeks/{week}/visibility", PROGRAM_ID, "üç")
				.with(authentication(signedIn()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"HIDDEN\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(programWeekPlanService);
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "coordinator@example.test", UserRole.COORDINATOR,
						UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}
}
