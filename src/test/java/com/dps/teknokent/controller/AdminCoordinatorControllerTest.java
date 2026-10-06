package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.CoordinatorCandidateStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.CoordinatorAccountResponse;
import com.dps.teknokent.dto.responses.CoordinatorCandidateResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AdminCoordinatorService;

@WebMvcTest(AdminCoordinatorController.class)
@Import(SecurityConfig.class)
class AdminCoordinatorControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AdminCoordinatorService adminCoordinatorService;

	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
	private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID COORDINATOR_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/admin/coordinators/candidate").param("email", "a@b.com"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(adminCoordinatorService);
	}

	@Test
	void checksACandidateAsTheSignedInAdminWhateverActorIdIsSent() throws Exception {
		when(adminCoordinatorService.candidate(ADMIN_ID, "ayse@mentor.com")).thenReturn(new CoordinatorCandidateResponse(
				"ayse@mentor.com", CoordinatorCandidateStatus.PROMOTABLE, OTHER_ID, "Ayşe Demir", UserRole.MENTOR));

		mockMvc.perform(get("/api/admin/coordinators/candidate")
						.param("email", "ayse@mentor.com")
						.param("actorId", OTHER_ID.toString())
						.with(authentication(signedIn())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTABLE"))
				.andExpect(jsonPath("$.role").value("MENTOR"));
	}

	@Test
	void addsACoordinator() throws Exception {
		when(adminCoordinatorService.add(eq(ADMIN_ID), any())).thenReturn(new CoordinatorAccountResponse(
				COORDINATOR_ID, "elif@teknokent.org", "Elif Demir", UserAccountStatus.APPROVED,
				CoordinatorCandidateStatus.NEW));

		mockMvc.perform(post("/api/admin/coordinators")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Elif Demir\",\"email\":\"elif@teknokent.org\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.outcome").value("NEW"));
	}

	@Test
	void passesALoginSetupFailureOnWithItsReason() throws Exception {
		when(adminCoordinatorService.add(eq(ADMIN_ID), any())).thenThrow(new ResponseStatusException(
				HttpStatus.BAD_GATEWAY, "Koordinatörün giriş hesabı Firebase'de oluşturulamadı (PERMISSION_DENIED)."));

		mockMvc.perform(post("/api/admin/coordinators")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Elif Demir\",\"email\":\"elif@teknokent.org\"}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.message")
						.value("Koordinatörün giriş hesabı Firebase'de oluşturulamadı (PERMISSION_DENIED)."));
	}

	@Test
	void rejectsABadEmailBeforeReachingTheService() throws Exception {
		mockMvc.perform(post("/api/admin/coordinators")
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Elif\",\"email\":\"not-an-email\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(adminCoordinatorService);
	}

	@Test
	void deactivatesACoordinator() throws Exception {
		when(adminCoordinatorService.setAccess(ADMIN_ID, COORDINATOR_ID, false)).thenReturn(new CoordinatorAccountResponse(
				COORDINATOR_ID, "elif@teknokent.org", "Elif Demir", UserAccountStatus.REJECTED, null));

		mockMvc.perform(put("/api/admin/coordinators/{id}/access", COORDINATOR_ID)
						.with(authentication(signedIn()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"active\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));
		verify(adminCoordinatorService).setAccess(ADMIN_ID, COORDINATOR_ID, false);
	}

	private static UsernamePasswordAuthenticationToken signedIn() {
		return new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(ADMIN_ID, "arya@admin.tnt", UserRole.ADMIN, UserAccountStatus.APPROVED, "uid"),
				null, List.of());
	}
}
