package com.dps.teknokent.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.service.PeerDailyReportService;
import com.dps.teknokent.service.PeerMeetingReportService;
import com.dps.teknokent.service.PeerWeeklyReportService;

@WebMvcTest(PeerReportController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.edition=ODTU")
class PeerReportControllerTest {

	private static final String ACTION = "/api/peer-mentoring/reports/meetings/{meetingId}/actions/{actionId}";
	private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID MEETING_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
	private static final UUID ACTION_ID = UUID.fromString("61000000-0000-0000-0000-000000000001");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PeerMeetingReportService meetingReportService;
	@MockitoBean
	private PeerDailyReportService dailyReportService;
	@MockitoBean
	private PeerWeeklyReportService weeklyReportService;

	@Test
	void ticksAnActionStepOff() throws Exception {
		mockMvc.perform(patch(ACTION, MEETING_ID, ACTION_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\", \"done\": false}"))
				.andExpect(status().isOk());

		verify(meetingReportService).updateAction(ACTOR_ID, MEETING_ID, ACTION_ID, false);
	}

	// Without the check a body that forgot "done" would untick the step.
	@Test
	void rejectsAnActionUpdateThatDoesNotSayWhetherTheStepIsDone() throws Exception {
		mockMvc.perform(patch(ACTION, MEETING_ID, ACTION_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\": \"" + ACTOR_ID + "\"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(meetingReportService);
	}
}
