package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.dps.teknokent.dto.requests.EmailCampaignRequest;
import com.dps.teknokent.dto.requests.SendEmailCampaignRequest;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.EmailCampaignService;
import com.dps.teknokent.service.EmailSuppressionService;

@WebMvcTest(EmailCenterController.class)
@Import(SecurityConfig.class)
class EmailCenterControllerTest {

	private static final UUID SIGNED_IN = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID FORGED = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
	private static final UUID ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final String DRAFT = "{\"actorId\":\"" + FORGED + "\",\"programId\":\"" + ID
			+ "\",\"audience\":\"MENTORS\",\"subject\":\"Konu\",\"bodyHtml\":\"<p>a</p>\"}";

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private EmailCampaignService campaignService;
	@MockitoBean
	private EmailSuppressionService suppressionService;

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/email-center/campaigns")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/email-center/unsubscribe").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"t\"}")).andExpect(status().isUnauthorized());
		verifyNoInteractions(campaignService, suppressionService);
	}

	@Test
	void everyCallActsAsTheSignedInPersonAndNeverAsTheIdInTheRequest() throws Exception {
		mockMvc.perform(get("/api/email-center/options").param("actorId", FORGED.toString())
				.with(authentication(signedIn()))).andExpect(status().isOk());
		mockMvc.perform(get("/api/email-center/templates").param("programId", ID.toString())
				.with(authentication(signedIn()))).andExpect(status().isOk());
		mockMvc.perform(get("/api/email-center/campaigns").with(authentication(signedIn())))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/email-center/campaigns/{id}", ID).with(authentication(signedIn())))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/email-center/campaigns").contentType(MediaType.APPLICATION_JSON).content(DRAFT)
				.with(authentication(signedIn()))).andExpect(status().isCreated());
		mockMvc.perform(put("/api/email-center/campaigns/{id}", ID).contentType(MediaType.APPLICATION_JSON)
				.content(DRAFT).with(authentication(signedIn()))).andExpect(status().isOk());
		mockMvc.perform(delete("/api/email-center/campaigns/{id}", ID).with(authentication(signedIn())))
				.andExpect(status().isNoContent());
		for (String step : List.of("test", "review", "stop")) {
			mockMvc.perform(post("/api/email-center/campaigns/{id}/" + step, ID)
					.contentType(MediaType.APPLICATION_JSON).content("{\"actorId\":\"" + FORGED + "\"}")
					.with(authentication(signedIn()))).andExpect(status().isOk());
		}
		mockMvc.perform(post("/api/email-center/campaigns/{id}/send", ID).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reviewDigest\":\"d\",\"untickedIds\":[],\"expectedCount\":1}")
				.with(authentication(signedIn()))).andExpect(status().isOk());

		verify(campaignService).options(SIGNED_IN);
		verify(campaignService).templates(SIGNED_IN, ID);
		verify(campaignService).list(SIGNED_IN);
		verify(campaignService).get(SIGNED_IN, ID);
		verify(campaignService).create(eq(SIGNED_IN), any(EmailCampaignRequest.class));
		verify(campaignService).update(eq(SIGNED_IN), eq(ID), any(EmailCampaignRequest.class));
		verify(campaignService).delete(SIGNED_IN, ID);
		verify(campaignService).sendTest(SIGNED_IN, ID);
		verify(campaignService).review(SIGNED_IN, ID);
		verify(campaignService).stop(SIGNED_IN, ID);
		verify(campaignService).send(eq(SIGNED_IN), eq(ID), any(SendEmailCampaignRequest.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"programId\":\"30000000-0000-0000-0000-000000000001\",\"audience\":\"MENTORS\",\"subject\":\" \",\"bodyHtml\":\"a\"}",
			"{\"programId\":\"30000000-0000-0000-0000-000000000001\",\"audience\":\"MENTORS\",\"subject\":\"a\\r\\nBcc: x@y.z\",\"bodyHtml\":\"a\"}",
			"{\"programId\":\"30000000-0000-0000-0000-000000000001\",\"subject\":\"a\",\"bodyHtml\":\"a\"}" })
	void refusesADraftWithoutASubjectAnAudienceOrWithAHeaderInItsSubject(String body) throws Exception {
		mockMvc.perform(post("/api/email-center/campaigns").contentType(MediaType.APPLICATION_JSON).content(body)
				.with(authentication(signedIn()))).andExpect(status().isBadRequest());
		verifyNoInteractions(campaignService);
	}

	@Test
	void unsubscribingNeedsOnlyTheToken() throws Exception {
		mockMvc.perform(post("/api/email-center/unsubscribe").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"abc\"}").with(authentication(signedIn()))).andExpect(status().isNoContent());
		verify(suppressionService).unsubscribe("abc");
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(SIGNED_IN, "elif@tekno.test",
				UserRole.COORDINATOR, UserAccountStatus.APPROVED, "uid"), null, List.of());
	}
}
