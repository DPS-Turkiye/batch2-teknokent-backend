package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.MentorLabelColor;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.MentorLabelRequest;
import com.dps.teknokent.dto.responses.MentorLabelResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.MentorLabelService;

@WebMvcTest(MentorLabelController.class)
@Import(SecurityConfig.class)
class MentorLabelControllerTest {

	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID SIGNED_IN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
	private static final UUID LABEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
	private static final MentorLabelResponse.Board BOARD = new MentorLabelResponse.Board(true, true,
			List.of(new MentorLabelResponse.BoardLabel(LABEL_ID, "Fintech", MentorLabelColor.GREEN, 1)),
			List.of(new MentorLabelResponse.MentorLabels(MENTOR_ID, List.of(LABEL_ID))));

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MentorLabelService mentorLabelService;

	@Test
	void refusesAnAnonymousCaller() throws Exception {
		mockMvc.perform(get("/api/coordinator/programs/{id}/mentor-labels", PROGRAM_ID))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/mentor-labels/directory")).andExpect(status().isUnauthorized());
		verifyNoInteractions(mentorLabelService);
	}

	@Test
	void readsTheBoardForTheActorTheProxyNames() throws Exception {
		when(mentorLabelService.board(ACTOR_ID, PROGRAM_ID)).thenReturn(BOARD);

		mockMvc.perform(get("/api/coordinator/programs/{id}/mentor-labels", PROGRAM_ID)
				.param("actorId", ACTOR_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.labels[0].color").value("GREEN"))
				.andExpect(jsonPath("$.labels[0].mentorCount").value(1))
				.andExpect(jsonPath("$.mentors[0].labelIds[0]").value(LABEL_ID.toString()));
	}

	@Test
	void createsAsTheSignedInUserWhateverActorIdIsSent() throws Exception {
		when(mentorLabelService.create(eq(SIGNED_IN_ID), eq(PROGRAM_ID), any())).thenReturn(BOARD);

		mockMvc.perform(post("/api/coordinator/programs/{id}/mentor-labels", PROGRAM_ID)
				.with(authentication(signedIn()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + ACTOR_ID + "\",\"name\":\"Fintech\",\"color\":\"GREEN\"}"))
				.andExpect(status().isOk());

		verify(mentorLabelService).create(SIGNED_IN_ID, PROGRAM_ID,
				new MentorLabelRequest(ACTOR_ID, "Fintech", MentorLabelColor.GREEN));
	}

	@Test
	void refusesAColorOutsideThePaletteANameOverThirtyAndABlankName() throws Exception {
		for (String body : List.of(
				"{\"name\":\"Fintech\",\"color\":\"#ff0000\"}",
				"{\"name\":\"Fintech\"}",
				"{\"name\":\"" + "a".repeat(31) + "\",\"color\":\"BLUE\"}",
				"{\"name\":\"  \",\"color\":\"BLUE\"}")) {
			mockMvc.perform(put("/api/coordinator/programs/{id}/mentor-labels/{labelId}", PROGRAM_ID, LABEL_ID)
					.with(user("test-user"))
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(mentorLabelService);
	}

	@Test
	void setsAMentorsLabelsAndRefusesMoreThanFive() throws Exception {
		when(mentorLabelService.setMentorLabels(ACTOR_ID, PROGRAM_ID, MENTOR_ID, List.of(LABEL_ID)))
				.thenReturn(BOARD);

		mockMvc.perform(put("/api/coordinator/programs/{id}/mentors/{mentorId}/labels", PROGRAM_ID, MENTOR_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + ACTOR_ID + "\",\"labelIds\":[\"" + LABEL_ID + "\"]}"))
				.andExpect(status().isOk());

		String six = String.join(",", java.util.stream.IntStream.range(0, 6)
				.mapToObj(index -> "\"" + UUID.randomUUID() + "\"").toList());
		mockMvc.perform(put("/api/coordinator/programs/{id}/mentors/{mentorId}/labels", PROGRAM_ID, MENTOR_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + ACTOR_ID + "\",\"labelIds\":[" + six + "]}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(put("/api/coordinator/programs/{id}/mentors/{mentorId}/labels", PROGRAM_ID, MENTOR_ID)
				.with(user("test-user"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + ACTOR_ID + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void deletesALabel() throws Exception {
		when(mentorLabelService.delete(ACTOR_ID, PROGRAM_ID, LABEL_ID)).thenReturn(BOARD);

		mockMvc.perform(delete("/api/coordinator/programs/{id}/mentor-labels/{labelId}", PROGRAM_ID, LABEL_ID)
				.param("actorId", ACTOR_ID.toString())
				.with(user("test-user")))
				.andExpect(status().isOk());
	}

	@Test
	void readsTheMenteeDirectoryAndAMentorsLabelsAsTheSignedInUser() throws Exception {
		when(mentorLabelService.directory(SIGNED_IN_ID))
				.thenReturn(new MentorLabelResponse.Directory(PROGRAM_ID, List.of(), List.of()));
		when(mentorLabelService.ofMentor(MENTOR_ID, SIGNED_IN_ID)).thenReturn(List.of(
				new MentorLabelResponse.ProgramLabels(PROGRAM_ID, "Build '26",
						List.of(new MentorLabelResponse.Label(LABEL_ID, "Fintech", MentorLabelColor.GREEN)))));

		mockMvc.perform(get("/api/mentor-labels/directory")
				.param("actorId", ACTOR_ID.toString())
				.with(authentication(signedIn())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()));
		mockMvc.perform(get("/api/mentor-labels/mentors/{mentorId}", MENTOR_ID)
				.param("actorId", ACTOR_ID.toString())
				.with(authentication(signedIn())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].programName").value("Build '26"))
				.andExpect(jsonPath("$[0].labels[0].name").value("Fintech"));
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN_ID, "elif@tekno.test", UserRole.COORDINATOR, UserAccountStatus.APPROVED,
						"uid"),
				null, List.of());
	}
}
