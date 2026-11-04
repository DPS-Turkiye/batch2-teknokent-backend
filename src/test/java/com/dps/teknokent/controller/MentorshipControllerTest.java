package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.service.MentorshipService;

@WebMvcTest(MentorshipController.class)
@Import(SecurityConfig.class)
class MentorshipControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MentorshipService mentorshipService;

	@Test
	void requiresAuthenticationForList() throws Exception {
		mockMvc.perform(get("/api/mentorships"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void returnsMentorships() throws Exception {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000301");

		when(mentorshipService.findAll()).thenReturn(List.of(new MentorshipResponse(
				mentorshipId,
				mentorId,
				"Dr. Elif Yılmaz",
				organizationId,
				"Teknokent",
				MentorshipStatus.ACTIVE,
				LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 12, 1),
				1,
				"Product-market fit validation and go-to-market planning",
				List.of(),
				0, null, null)));

		mockMvc.perform(get("/api/mentorships").with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(mentorshipId.toString()))
				.andExpect(jsonPath("$[0].mentorId").value(mentorId.toString()))
				.andExpect(jsonPath("$[0].organizationName").value("Teknokent"))
				.andExpect(jsonPath("$[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$[0].daysPerWeek").value(1));
	}

	@Test
	void createsMentorship() throws Exception {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000302");

		when(mentorshipService.create(any(CreateMentorshipRequest.class))).thenReturn(new MentorshipResponse(
				mentorshipId,
				mentorId,
				"Dr. Elif Yılmaz",
				organizationId,
				"Teknokent",
				MentorshipStatus.PLANNED,
				LocalDate.of(2026, 9, 10),
				LocalDate.of(2026, 11, 10),
				1,
				"Define roadmap and validate product strategy",
				List.of(),
				0, null, null));

		mockMvc.perform(post("/api/mentorships")
						.with(user("test-user"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "mentorId": "00000000-0000-0000-0000-000000000101",
								  "organizationId": "00000000-0000-0000-0000-000000000201",
								  "status": "PLANNED",
								  "startDate": "2026-09-10",
								  "endDate": "2026-11-10",
								  "daysPerWeek": 1,
								  "goals": "Define roadmap and validate product strategy"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(mentorshipId.toString()))
				.andExpect(jsonPath("$.mentorId").value(mentorId.toString()))
				.andExpect(jsonPath("$.status").value("PLANNED"))
				.andExpect(jsonPath("$.goals").value("Define roadmap and validate product strategy"));
	}

	@Test
	void returnsEmptyListWhenNoMentorshipExists() throws Exception {
		when(mentorshipService.findAll()).thenReturn(List.of());

		mockMvc.perform(get("/api/mentorships").with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void rejectsCreateMentorshipWhenStatusIsInvalid() throws Exception {
		mockMvc.perform(post("/api/mentorships")
						.with(user("test-user"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "mentorId": "00000000-0000-0000-0000-000000000101",
								  "organizationId": "00000000-0000-0000-0000-000000000201",
								  "status": "NOT_A_STATUS",
								  "startDate": "2026-09-10",
								  "goals": "Define roadmap and validate product strategy"
								}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsCreateMentorshipWhenRequiredFieldsAreMissing() throws Exception {
		mockMvc.perform(post("/api/mentorships")
						.with(user("test-user"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "mentorId": "00000000-0000-0000-0000-000000000101",
								  "organizationId": "00000000-0000-0000-0000-000000000201",
								  "status": "PLANNED",
								  "startDate": null
								}
								"""))
				.andExpect(status().isBadRequest());
	}
}
