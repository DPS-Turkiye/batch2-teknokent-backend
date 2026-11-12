package com.dps.teknokent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.PeerExperienceKind;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.PeerTopicLevel;
import com.dps.teknokent.dto.requests.SavePeerMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilityRuleResponse;
import com.dps.teknokent.dto.responses.PeerCategoryCountResponse;
import com.dps.teknokent.dto.responses.PeerExperienceCardResponse;
import com.dps.teknokent.dto.responses.PeerMentorDetailResponse;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.service.PeerAppointmentService;
import com.dps.teknokent.service.PeerMentoringService;

@WebMvcTest(PeerMentoringController.class)
@Import(SecurityConfig.class)
class PeerMentoringControllerTest {

	private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID MENTOR = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PeerMentoringService peerMentoringService;

	@MockitoBean
	private PeerAppointmentService peerAppointmentService;

	@Test
	void theNewReadsNeedTheServiceCredential() throws Exception {
		mockMvc.perform(get("/api/peer-mentoring/mentors/" + MENTOR)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/peer-mentoring/mentor-counts")).andExpect(status().isUnauthorized());
		verifyNoInteractions(peerMentoringService);
	}

	@Test
	void theDirectoryPassesTheSubTopicAndThisWeekFilters() throws Exception {
		mockMvc.perform(get("/api/peer-mentoring/mentors")
				.param("actorId", ACTOR.toString())
				.param("category", "CAREER_INTERNSHIP")
				.param("subTopic", "cv")
				.param("availableThisWeek", "true")
				.with(user("proxy")))
				.andExpect(status().isOk());

		verify(peerMentoringService).mentors(ACTOR, PeerTopicCategory.CAREER_INTERNSHIP, null, "cv", true);
	}

	@Test
	void withoutTheNewFiltersTheDirectoryAnswersAsBefore() throws Exception {
		mockMvc.perform(get("/api/peer-mentoring/mentors").param("actorId", ACTOR.toString()).with(user("proxy")))
				.andExpect(status().isOk());

		verify(peerMentoringService).mentors(ACTOR, null, null, null, false);
	}

	@Test
	void oneMentorsProfileCarriesTheWizardAnswersAndHoursButNoEmail() throws Exception {
		when(peerMentoringService.mentor(ACTOR, MENTOR)).thenReturn(new PeerMentorDetailResponse(MENTOR,
				"Zeynep Demir", "Bilgisayar Mühendisliği", 4, List.of(PeerTopicCategory.CAREER_INTERNSHIP),
				"CV ve staj", List.of(), false, List.of(), true, List.of("cv"), List.of("CENG 315"), List.of(),
				null, null, List.of(new MentorAvailabilityRuleResponse(UUID.randomUUID(), DayOfWeek.TUESDAY,
						LocalTime.of(18, 0), LocalTime.of(20, 0), 60)),
				Map.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED),
				List.of(new PeerExperienceCardResponse(PeerExperienceKind.INTERNSHIP, "Yazılım stajyeri", "Aselsan",
						YearMonth.of(2025, 6), null, null))));

		mockMvc.perform(get("/api/peer-mentoring/mentors/" + MENTOR).param("actorId", ACTOR.toString())
				.with(user("proxy")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mentorId").value(MENTOR.toString()))
				.andExpect(jsonPath("$.availableThisWeek").value(true))
				.andExpect(jsonPath("$.subTopics[0]").value("cv"))
				.andExpect(jsonPath("$.weeklyHours[0].dayOfWeek").value("TUESDAY"))
				.andExpect(jsonPath("$.levels.CAREER_INTERNSHIP").value("ADVANCED"))
				.andExpect(jsonPath("$.experiences[0].startedOn").value("2025-06"))
				.andExpect(jsonPath("$.experiences[0].endedOn").doesNotExist())
				.andExpect(jsonPath("$.email").doesNotExist());
	}

	@Test
	void aMentorTheDirectoryDoesNotListIsNotFound() throws Exception {
		when(peerMentoringService.mentor(ACTOR, MENTOR)).thenThrow(new ResourceNotFoundException("Peer mentor not found"));

		mockMvc.perform(get("/api/peer-mentoring/mentors/" + MENTOR).param("actorId", ACTOR.toString())
				.with(user("proxy")))
				.andExpect(status().isNotFound());
	}

	@Test
	void theWizardsCardsAndLevelsBindFromJsonWithMonthsAsYearAndMonth() throws Exception {
		mockMvc.perform(put("/api/peer-mentoring/profile").with(user("proxy"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"actorId": "%s", "categories": ["CAREER_INTERNSHIP"], "subTopics": [],
						 "helpWith": "Staj başvurusunda yardımcı olabilirim.",
						 "levels": {"CAREER_INTERNSHIP": "INTERMEDIATE"},
						 "experiences": [{"kind": "INTERNSHIP", "title": "Staj", "startedOn": "2025-06"}]}
						""".formatted(ACTOR)))
				.andExpect(status().isOk());

		ArgumentCaptor<SavePeerMentorProfileRequest> saved = ArgumentCaptor.forClass(SavePeerMentorProfileRequest.class);
		verify(peerMentoringService).saveProfile(org.mockito.ArgumentMatchers.eq(ACTOR), saved.capture());
		assertThat(saved.getValue().levels()).containsEntry(PeerTopicCategory.CAREER_INTERNSHIP,
				PeerTopicLevel.INTERMEDIATE);
		assertThat(saved.getValue().experiences()).singleElement()
				.satisfies(card -> assertThat(card.startedOn()).isEqualTo(YearMonth.of(2025, 6)));
	}

	@Test
	void aCardWithoutAKindOrStartIsABadRequest() throws Exception {
		mockMvc.perform(put("/api/peer-mentoring/profile").with(user("proxy"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"actorId": "%s", "categories": ["CAREER_INTERNSHIP"], "subTopics": [],
						 "helpWith": "Staj başvurusunda yardımcı olabilirim.",
						 "experiences": [{"title": "Staj"}]}
						""".formatted(ACTOR)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(peerMentoringService);
	}

	@Test
	void countsComePerActiveCategory() throws Exception {
		when(peerMentoringService.mentorCounts(ACTOR)).thenReturn(List.of(
				new PeerCategoryCountResponse(PeerTopicCategory.CAREER_INTERNSHIP, 3)));

		mockMvc.perform(get("/api/peer-mentoring/mentor-counts").param("actorId", ACTOR.toString())
				.with(user("proxy")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].category").value("CAREER_INTERNSHIP"))
				.andExpect(jsonPath("$[0].mentorCount").value(3));
	}
}
