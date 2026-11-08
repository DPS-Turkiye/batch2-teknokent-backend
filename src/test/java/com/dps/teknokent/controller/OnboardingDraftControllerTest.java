package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.requests.UpsertOnboardingDraftRequest;
import com.dps.teknokent.dto.responses.OnboardingDraftResponse;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.service.OnboardingDraftService;

@WebMvcTest(OnboardingDraftController.class)
@Import(SecurityConfig.class)
class OnboardingDraftControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OnboardingDraftService onboardingDraftService;

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/onboarding/draft"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsMachineCredentialsWithoutFirebase() throws Exception {
		mockMvc.perform(get("/api/onboarding/draft").with(user("test-user")))
				.andExpect(status().isForbidden());
	}

	@Test
	void returnsNoContentWhenNothingIsStored() throws Exception {
		when(onboardingDraftService.find("uid-1")).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/onboarding/draft").with(firebase()))
				.andExpect(status().isNoContent());
	}

	@Test
	void returnsTheStoredDraft() throws Exception {
		when(onboardingDraftService.find("uid-1")).thenReturn(Optional.of(new OnboardingDraftResponse(
				"signup-wizard",
				"2",
				Map.of("bio", "Kurucu"),
				Instant.parse("2026-09-04T08:00:00Z"))));

		mockMvc.perform(get("/api/onboarding/draft").with(firebase()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("signup-wizard"))
				.andExpect(jsonPath("$.step").value("2"))
				.andExpect(jsonPath("$.payload.bio").value("Kurucu"));
	}

	@Test
	void upsertsTheDraftForTheSignedInFirebaseUser() throws Exception {
		when(onboardingDraftService.upsert(eq("uid-1"), any(UpsertOnboardingDraftRequest.class)))
				.thenReturn(new OnboardingDraftResponse(
						"signup-wizard",
						"1",
						Map.of("bio", "Kurucu"),
						Instant.parse("2026-09-04T08:00:00Z")));

		mockMvc.perform(put("/api/onboarding/draft")
						.with(firebase())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "kind": "signup-wizard",
								  "step": "1",
								  "payload": { "bio": "Kurucu" }
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("signup-wizard"));
	}

	@Test
	void deletesTheDraft() throws Exception {
		mockMvc.perform(delete("/api/onboarding/draft").with(firebase()))
				.andExpect(status().isNoContent());

		verify(onboardingDraftService).delete("uid-1");
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor firebase() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
				new FirebaseIdentity("uid-1", "ayse@startup.com", true), "firebase",
				List.of(new SimpleGrantedAuthority("ROLE_REGISTERING"))));
	}
}
