package com.dps.teknokent.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.CoordinatorMentorshipHealth;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.CoordinatorCalendarResponse;
import com.dps.teknokent.dto.responses.CoordinatorMentorshipResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.CoordinatorCalendarService;
import com.dps.teknokent.service.CoordinatorMentorshipService;

@WebMvcTest({ CoordinatorMentorshipController.class, CoordinatorCalendarController.class })
@Import(SecurityConfig.class)
class CoordinatorMentorshipControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CoordinatorMentorshipService coordinatorMentorshipService;
	@MockitoBean
	private CoordinatorCalendarService coordinatorCalendarService;

	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID MENTORSHIP_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/coordinator/mentorships")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/coordinator/calendar").param("from", "2026-09-14").param("to", "2026-09-20"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(coordinatorMentorshipService, coordinatorCalendarService);
	}

	@Test
	void listsTheSignedInCoordinatorsMentorshipsWhateverActorIdIsSent() throws Exception {
		when(coordinatorMentorshipService.list(SIGNED_IN_ID, PROGRAM_ID)).thenReturn(List.of(row()));

		mockMvc.perform(get("/api/coordinator/mentorships")
						.param("programId", PROGRAM_ID.toString())
						.param("actorId", OTHER_ID.toString())
						.with(authentication(signedIn())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].organizationName").value("Lumen"))
				.andExpect(jsonPath("$[0].health").value("RISKY"));
	}

	@Test
	void aReminderAnswersNoContent() throws Exception {
		mockMvc.perform(post("/api/coordinator/mentorships/{id}/remind", MENTORSHIP_ID).with(authentication(signedIn())))
				.andExpect(status().isNoContent());

		verify(coordinatorMentorshipService).remind(SIGNED_IN_ID, MENTORSHIP_ID);
	}

	@Test
	void theCalendarParsesItsDayRange() throws Exception {
		LocalDate from = LocalDate.of(2026, 9, 14);
		LocalDate to = LocalDate.of(2026, 9, 20);
		when(coordinatorCalendarService.calendar(SIGNED_IN_ID, null, from, to)).thenReturn(new CoordinatorCalendarResponse(
				from, to, List.of(), List.of(), new CoordinatorCalendarResponse.Summary(18, 2, 2, 2), 2));

		mockMvc.perform(get("/api/coordinator/calendar")
						.param("from", "2026-09-14")
						.param("to", "2026-09-20")
						.with(authentication(signedIn())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.meetings").value(18));
	}

	@Test
	void theCalendarNeedsARange() throws Exception {
		mockMvc.perform(get("/api/coordinator/calendar").with(authentication(signedIn())))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(coordinatorCalendarService);
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "elif@tekno.test", UserRole.COORDINATOR, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}

	private static CoordinatorMentorshipResponse row() {
		return new CoordinatorMentorshipResponse(
				MENTORSHIP_ID, PROGRAM_ID, "METU Stars 2026", UUID.randomUUID(), "Ayşe Demir", null,
				UUID.randomUUID(), "Lumen", MentorshipStatus.ACTIVE, CoordinatorMentorshipHealth.RISKY, "Finansal model",
				null, null, 18L, null, 2, 1, 0);
	}
}
