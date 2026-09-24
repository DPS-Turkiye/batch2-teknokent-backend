package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.UpdateNotificationPreferencesRequest;
import com.dps.teknokent.dto.responses.NotificationPreferencesResponse;
import com.dps.teknokent.dto.responses.NotificationUnreadCountResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.NotificationPreferenceService;
import com.dps.teknokent.service.NotificationService;
import com.dps.teknokent.service.PushTokenService;

@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerTest {

	private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private NotificationService notificationService;

	@MockitoBean
	private NotificationPreferenceService notificationPreferenceService;

	@MockitoBean
	private PushTokenService pushTokenService;

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/notifications/unread-count"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void returnsTheCallersUnreadCount() throws Exception {
		when(notificationService.unreadCount(USER_ID)).thenReturn(new NotificationUnreadCountResponse(4));

		mockMvc.perform(get("/api/notifications/unread-count").with(authentication(signedIn())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unread").value(4));
	}

	@Test
	void marksEveryNotificationOfTheCallerRead() throws Exception {
		mockMvc.perform(patch("/api/notifications/read-all").with(authentication(signedIn())))
				.andExpect(status().isNoContent());

		verify(notificationService).markAllRead(USER_ID);
	}

	@Test
	void savesPreferencesForTheCaller() throws Exception {
		when(notificationPreferenceService.update(eq(USER_ID), any(UpdateNotificationPreferencesRequest.class)))
				.thenReturn(new NotificationPreferencesResponse(false, true, true, true, false, true));

		mockMvc.perform(put("/api/notifications/preferences")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "messages": false, "meetingReminders": true, "meetingChanges": true,
								  "newTasks": true, "taskDeadlines": false, "startupActivity": true }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.messages").value(false))
				.andExpect(jsonPath("$.taskDeadlines").value(false));
	}

	@Test
	void rejectsPreferencesWithAMissingChoice() throws Exception {
		mockMvc.perform(put("/api/notifications/preferences")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "messages": false }
								"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(notificationPreferenceService);
	}

	@Test
	void registersAPushTokenWithTheBrowsersUserAgent() throws Exception {
		mockMvc.perform(post("/api/notifications/push-tokens")
						.with(authentication(signedIn()))
						.header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (iPhone)")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "token": "fcm-token" }
								"""))
				.andExpect(status().isNoContent());

		verify(pushTokenService).register(USER_ID, "fcm-token", "Mozilla/5.0 (iPhone)");
	}

	@Test
	void rejectsABlankPushToken() throws Exception {
		mockMvc.perform(post("/api/notifications/push-tokens")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "token": " " }
								"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(pushTokenService);
	}

	@Test
	void unregistersThePushTokenOnSignOut() throws Exception {
		mockMvc.perform(delete("/api/notifications/push-tokens")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "token": "fcm-token" }
								"""))
				.andExpect(status().isNoContent());

		verify(pushTokenService).unregister(USER_ID, "fcm-token");
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(USER_ID, "founder@dongu.co", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}
}
