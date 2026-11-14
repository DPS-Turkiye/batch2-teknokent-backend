package com.dps.teknokent.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.dto.responses.AdminOverviewResponse;
import com.dps.teknokent.service.AdminOverviewService;

@WebMvcTest(AdminOverviewController.class)
@Import(SecurityConfig.class)
class AdminOverviewControllerTest {

	private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID PROGRAM_ID = UUID.fromString("90000000-0000-0000-0000-000000000001");
	private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID MENTOR_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final Instant AT = Instant.parse("2026-09-14T08:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AdminOverviewService adminOverviewService;

	// The frontend reads every status, type and severity as the enum's name.
	@Test
	void writesStatusesTypesAndSeveritiesAsTheirNames() throws Exception {
		when(adminOverviewService.overview(ACTOR_ID)).thenReturn(new AdminOverviewResponse(
				List.of(new AdminOverviewResponse.ProgramRow(PROGRAM_ID, "METU Stars 2026", ProgramStatus.ACTIVE,
						ProgramType.STARTUP_MENTORING, AT, null, null, List.of(), 0, 0, 1, List.of(),
						List.of(new AdminOverviewResponse.MentorMembershipRow(MENTOR_ID, "Kemal Arslan",
								"kemal@mentor.test", ProgramMentorStatus.PENDING)))),
				List.of(),
				List.of(new AdminOverviewResponse.MentorshipRow(UUID.randomUUID(), MENTOR_ID, "Kemal Arslan", ORG_ID,
						"Vega", MentorshipStatus.ACTIVE, LocalDate.parse("2026-09-01"), null, 40, 3L, 1, 2,
						PROGRAM_ID)),
				List.of(new AdminOverviewResponse.RequestRow(UUID.randomUUID(), ORG_ID, "Vega", MENTOR_ID,
						"Kemal Arslan", "Finans", MentorshipRequestStatus.MORE_INFO_REQUESTED, AT)),
				List.of(new AdminOverviewResponse.BottleneckRow(UUID.randomUUID(), "Hukuk", BottleneckSeverity.HIGH,
						BottleneckStatus.IN_PROGRESS, ORG_ID, "Vega", AT)),
				1,
				List.of(new AdminOverviewResponse.MeetingMark(MENTOR_ID, ORG_ID, AT, MeetingStatus.COMPLETED))));

		mockMvc.perform(get("/api/admin/overview").param("actorId", ACTOR_ID.toString()).with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.programs[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.programs[0].type").value("STARTUP_MENTORING"))
				.andExpect(jsonPath("$.programs[0].mentors[0].status").value("PENDING"))
				.andExpect(jsonPath("$.mentorships[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.requests[0].status").value("MORE_INFO_REQUESTED"))
				.andExpect(jsonPath("$.bottlenecks[0].severity").value("HIGH"))
				.andExpect(jsonPath("$.bottlenecks[0].status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.meetings[0].status").value("COMPLETED"));
	}
}
