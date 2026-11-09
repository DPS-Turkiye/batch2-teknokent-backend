package com.dps.teknokent.controller;

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
import com.dps.teknokent.dto.responses.RealtimeTokenResponse;
import com.dps.teknokent.service.RealtimeTokenService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RealtimeTokenController.class)
@Import(SecurityConfig.class)
class RealtimeTokenControllerTest {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final Instant SIGNED_IN_AT = Instant.parse("2026-11-08T08:15:30.123Z");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RealtimeTokenService realtimeTokenService;

	@Test
	void issuesATokenForTheSessionsSignIn() throws Exception {
		when(realtimeTokenService.issue(USER_ID, ORGANIZATION_ID, SIGNED_IN_AT))
				.thenReturn(new RealtimeTokenResponse("token", SIGNED_IN_AT.plusSeconds(60)));

		mockMvc.perform(post("/api/realtime/token")
						.with(user("proxy"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"userId": "%s", "organizationId": "%s", "signedInAt": "2026-11-08T08:15:30.123Z"}
								""".formatted(USER_ID, ORGANIZATION_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").value("token"));
	}

	@Test
	void stillIssuesATokenWhenTheFrontendSendsNoSignIn() throws Exception {
		mockMvc.perform(post("/api/realtime/token")
						.with(user("proxy"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"userId": "%s", "organizationId": "%s"}
								""".formatted(USER_ID, ORGANIZATION_ID)))
				.andExpect(status().isOk());

		verify(realtimeTokenService).issue(eq(USER_ID), eq(ORGANIZATION_ID), isNull());
	}
}
