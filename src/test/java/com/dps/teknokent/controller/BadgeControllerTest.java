package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeKind;
import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.GateBadgeState;
import com.dps.teknokent.dto.responses.AchievementProgressResponse;
import com.dps.teknokent.dto.responses.GateBadgeRowResponse;
import com.dps.teknokent.dto.responses.PublicBadgeResponse;
import com.dps.teknokent.service.AchievementService;
import com.dps.teknokent.service.BadgeService;

@WebMvcTest(BadgeController.class)
@Import(SecurityConfig.class)
// These endpoints belong to the ODTÜ deployment; the Teknokent default answers them with a 404.
@TestPropertySource(properties = "app.edition=ODTU")
class BadgeControllerTest {

	private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID STUDENT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
	private static final UUID AWARD = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private BadgeService badgeService;

	@MockitoBean
	private AchievementService achievementService;

	@Test
	void needsTheServiceCredential() throws Exception {
		mockMvc.perform(get("/api/users/" + STUDENT + "/badges")).andExpect(status().isUnauthorized());
		verifyNoInteractions(badgeService);
	}

	@Test
	void showsAPersonsActiveBadges() throws Exception {
		when(badgeService.publicOf(STUDENT)).thenReturn(List.of(new PublicBadgeResponse(BadgeCode.PEER_MENTOR,
				BadgeKind.GATE, PROGRAM, Instant.parse("2026-11-08T10:00:00Z"))));

		mockMvc.perform(get("/api/users/" + STUDENT + "/badges").with(user("proxy")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].code").value("PEER_MENTOR"))
				.andExpect(jsonPath("$[0].kind").value("GATE"))
				.andExpect(jsonPath("$[0].reason").doesNotExist());
	}

	@Test
	void showsTheActorsAchievementProgress() throws Exception {
		when(achievementService.mine(ACTOR)).thenReturn(List.of(new AchievementProgressResponse(BadgeCode.SESSIONS_10,
				false, null, 4, 10, null, null)));

		mockMvc.perform(get("/api/badges/mine/achievements").param("actorId", ACTOR.toString()).with(user("proxy")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].code").value("SESSIONS_10"))
				.andExpect(jsonPath("$[0].current").value(4))
				.andExpect(jsonPath("$[0].target").value(10));
	}

	@Test
	void awardsTheGateBadgeForTheActor() throws Exception {
		when(badgeService.awardGate(ACTOR, PROGRAM, STUDENT)).thenReturn(row(GateBadgeState.ACTIVE));

		mockMvc.perform(post("/api/coordinator/programs/" + PROGRAM + "/badges/awards")
						.with(user("proxy"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"actorId\":\"" + ACTOR + "\",\"userId\":\"" + STUDENT + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("ACTIVE"));
		verify(badgeService).awardGate(ACTOR, PROGRAM, STUDENT);
	}

	@Test
	void aRevokeWithoutAReasonNeverReachesTheService() throws Exception {
		mockMvc.perform(post("/api/coordinator/programs/" + PROGRAM + "/badges/awards/" + AWARD + "/revoke")
						.with(user("proxy"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"actorId\":\"" + ACTOR + "\",\"reason\":\"  \"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(badgeService);
	}

	@Test
	void filtersTheRosterByState() throws Exception {
		when(badgeService.roster(ACTOR, PROGRAM, GateBadgeState.ACTIVE)).thenReturn(List.of(row(GateBadgeState.ACTIVE)));

		mockMvc.perform(get("/api/coordinator/programs/" + PROGRAM + "/badges/roster")
						.param("actorId", ACTOR.toString())
						.param("state", "ACTIVE")
						.with(user("proxy")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].userId").value(STUDENT.toString()));
		verify(badgeService).roster(any(), any(), any());
	}

	private static GateBadgeRowResponse row(GateBadgeState state) {
		return new GateBadgeRowResponse(STUDENT, "Zeynep Öğrenci", "zeynep@odtu-dev.invalid", PROGRAM,
				"İŞKUR 2026 Bahar", state, AWARD, BadgeSource.COORDINATOR, Instant.parse("2026-11-08T10:00:00Z"),
				null, null, GateBadgeRowResponse.Training.NONE);
	}
}
