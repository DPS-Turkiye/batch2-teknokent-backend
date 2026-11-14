package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.FirebaseAuthConfig;
import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ReadWeeklyProgramRequest;
import com.dps.teknokent.dto.responses.WeeklyProgramProposalResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.security.FirebaseTokenVerifier;
import com.dps.teknokent.service.WeeklyProgramService;

/** The coordinator's own Firebase token, through the real filter chain, on the weekly program endpoints. */
@WebMvcTest(WeeklyProgramController.class)
@Import({ SecurityConfig.class, FirebaseAuthConfig.class })
class WeeklyProgramBearerAccessTest {

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
	private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private WeeklyProgramService weeklyProgramService;

	@MockitoBean
	private FirebaseTokenVerifier firebaseTokenVerifier;

	@MockitoBean
	private AppUserRepository appUserRepository;

	@BeforeEach
	void signedInCoordinator() {
		AppUser coordinator = new AppUser();
		coordinator.setId(COORDINATOR_ID);
		coordinator.setEmail("koordinator@example.com");
		coordinator.setRole(UserRole.COORDINATOR);
		coordinator.setStatus(UserAccountStatus.APPROVED);
		coordinator.setFirebaseUid("uid-c");
		when(firebaseTokenVerifier.enabled()).thenReturn(true);
		when(firebaseTokenVerifier.verify("good"))
				.thenReturn(Optional.of(new FirebaseIdentity("uid-c", coordinator.getEmail(), true)));
		when(appUserRepository.findByFirebaseUid("uid-c")).thenReturn(Optional.of(coordinator));
	}

	@Test
	void theCoordinatorsTokenReachesTheReadAsThemselves() throws Exception {
		when(weeklyProgramService.read(eq(COORDINATOR_ID), eq(PROGRAM_ID), any(ReadWeeklyProgramRequest.class)))
				.thenReturn(new WeeklyProgramProposalResponse(11, List.of(), List.of(), List.of(), List.of(),
						List.of(), 1, new WeeklyProgramProposalResponse.Existing(0, 0, null)));

		mockMvc.perform(post("/api/coordinator/programs/{id}/weekly-program/read", PROGRAM_ID)
						.header(HttpHeaders.AUTHORIZATION, "Bearer good")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"actorId\":\"" + OTHER_ID + "\",\"documentId\":\"" + DOCUMENT_ID + "\"}"))
				.andExpect(status().isOk());

		verify(weeklyProgramService).read(eq(COORDINATOR_ID), eq(PROGRAM_ID), any(ReadWeeklyProgramRequest.class));
	}

	@Test
	void theSaveStillRefusesATokenSoItGoesThroughTheProxy() throws Exception {
		mockMvc.perform(put("/api/coordinator/programs/{id}/weekly-program", PROGRAM_ID)
						.header(HttpHeaders.AUTHORIZATION, "Bearer good")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isForbidden());

		verifyNoInteractions(weeklyProgramService);
	}
}
