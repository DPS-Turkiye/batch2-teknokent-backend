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
import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.DeclineMeetingProposalRequest;
import com.dps.teknokent.dto.requests.ProposeMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingProposalResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.MeetingProposalService;

@WebMvcTest(MeetingProposalController.class)
@Import(SecurityConfig.class)
class MeetingProposalControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MeetingProposalService proposalService;

	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID MENTORSHIP_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID PROPOSAL_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/meeting-proposals")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/coordinator/meeting-proposals")).andExpect(status().isUnauthorized());
		verifyNoInteractions(proposalService);
	}

	@Test
	void aCoordinatorProposesAMeetingForAMentorship() throws Exception {
		when(proposalService.propose(eq(SIGNED_IN_ID), eq(MENTORSHIP_ID), any(ProposeMeetingRequest.class)))
				.thenReturn(proposal());

		mockMvc.perform(post("/api/coordinator/mentorships/{id}/meeting-proposals", MENTORSHIP_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"scheduledAt\":\"2026-10-01T07:30:00Z\",\"durationMinutes\":45}")
						.with(authentication(signedIn(UserRole.COORDINATOR))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void aProposalNeedsATimeAndASaneLength() throws Exception {
		mockMvc.perform(post("/api/coordinator/mentorships/{id}/meeting-proposals", MENTORSHIP_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"durationMinutes\":5}")
						.with(authentication(signedIn(UserRole.COORDINATOR))))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(proposalService);
	}

	@Test
	void theMentorOrTeamAnswersAsThemselves() throws Exception {
		when(proposalService.mine(SIGNED_IN_ID)).thenReturn(List.of(proposal()));
		when(proposalService.accept(SIGNED_IN_ID, PROPOSAL_ID)).thenReturn(proposal());

		mockMvc.perform(get("/api/meeting-proposals").with(authentication(signedIn(UserRole.MENTOR))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].organizationName").value("Lumen"));
		mockMvc.perform(post("/api/meeting-proposals/{id}/accept", PROPOSAL_ID)
						.with(authentication(signedIn(UserRole.MENTOR))))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/meeting-proposals/{id}/decline", PROPOSAL_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Seyahatteyim\"}")
						.with(authentication(signedIn(UserRole.MENTOR))))
				.andExpect(status().isOk());

		verify(proposalService).decline(SIGNED_IN_ID, PROPOSAL_ID, new DeclineMeetingProposalRequest(null, "Seyahatteyim"));
	}

	@Test
	void remindAndWithdrawAnswerNoContent() throws Exception {
		mockMvc.perform(post("/api/coordinator/meeting-proposals/{id}/remind", PROPOSAL_ID)
						.with(authentication(signedIn(UserRole.COORDINATOR))))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/coordinator/meeting-proposals/{id}", PROPOSAL_ID)
						.with(authentication(signedIn(UserRole.COORDINATOR))))
				.andExpect(status().isNoContent());

		verify(proposalService).remind(SIGNED_IN_ID, PROPOSAL_ID);
		verify(proposalService).withdraw(SIGNED_IN_ID, PROPOSAL_ID);
	}

	private static UsernamePasswordAuthenticationToken signedIn(UserRole role) {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "user@tekno.test", role, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}

	private static MeetingProposalResponse proposal() {
		return new MeetingProposalResponse(
				PROPOSAL_ID, MENTORSHIP_ID, UUID.randomUUID(), "METU Stars 2026", UUID.randomUUID(), "Ayşe Demir",
				UUID.randomUUID(), "Lumen", "Elif Demir", Instant.parse("2026-10-01T07:30:00Z"), 45, null, null,
				MeetingProposalStatus.PENDING, null, null, null, null, null, null, null, Instant.now(), true);
	}
}
