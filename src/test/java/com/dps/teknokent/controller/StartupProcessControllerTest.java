package com.dps.teknokent.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.security.AppUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.domain.StartupStageState;
import com.dps.teknokent.dto.requests.CreateStartupRoundRequest;
import com.dps.teknokent.dto.requests.SubmitProcessAssignmentRequest;
import com.dps.teknokent.dto.requests.UpdateStartupStageRequest;
import com.dps.teknokent.dto.responses.ProcessAssignmentResponse;
import com.dps.teknokent.dto.responses.StartupProcessResponse;
import com.dps.teknokent.dto.responses.StartupRoundResponse;
import com.dps.teknokent.dto.responses.StartupStageResponse;
import com.dps.teknokent.service.StartupProcessService;

@WebMvcTest(StartupProcessController.class)
@Import(SecurityConfig.class)
class StartupProcessControllerTest {

    private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ROUND_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StartupProcessService startupProcessService;

    private static StartupProcessResponse process(StartupStage currentStage) {
        StartupRoundResponse round = new StartupRoundResponse(ROUND_ID, "Value proposition & onboarding",
                "MVP test turu", StartupStage.MVP, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 31));
        ProcessAssignmentResponse assignment = new ProcessAssignmentResponse(ASSIGNMENT_ID, ROUND_ID,
                StartupStage.FINANCE, "Finansal model", null, ActionItemStatus.OPEN, null, null, null,
                Instant.parse("2026-08-10T09:00:00Z"));
        return new StartupProcessResponse(ORG_ID, currentStage,
                List.of(new StartupStageResponse(StartupStage.PROBLEM, StartupStageState.COMPLETED),
                        new StartupStageResponse(StartupStage.MVP, StartupStageState.CURRENT)),
                round, List.of(round), List.of(assignment));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/organizations/{id}/process", ORG_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheProcess() throws Exception {
        when(startupProcessService.get(ORG_ID, ACTOR_ID)).thenReturn(process(StartupStage.MVP));

        mockMvc.perform(get("/api/organizations/{id}/process", ORG_ID)
                        .param("actorId", ACTOR_ID.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("MVP"))
                .andExpect(jsonPath("$.stages[1].state").value("CURRENT"))
                .andExpect(jsonPath("$.currentRound.name").value("MVP test turu"))
                .andExpect(jsonPath("$.currentRound.startsOn").value("2026-08-02"))
                .andExpect(jsonPath("$.assignments[0].title").value("Finansal model"));
    }

    @Test
    void updatesTheStage() throws Exception {
        when(startupProcessService.updateStage(eq(ORG_ID), any(UpdateStartupStageRequest.class)))
                .thenReturn(process(StartupStage.BUSINESS_MODEL));

        mockMvc.perform(put("/api/organizations/{id}/process/stage", ORG_ID)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "actorId": "%s", "stage": "BUSINESS_MODEL" }
                                """.formatted(ACTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("BUSINESS_MODEL"));
    }

    @Test
    void rejectsAStageRequestWithoutAStage() throws Exception {
        mockMvc.perform(put("/api/organizations/{id}/process/stage", ORG_ID)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "actorId": "%s" }
                                """.formatted(ACTOR_ID)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(startupProcessService);
    }

    @Test
    void createsARound() throws Exception {
        when(startupProcessService.createRound(eq(ORG_ID), any(CreateStartupRoundRequest.class)))
                .thenReturn(process(StartupStage.MVP));

        mockMvc.perform(post("/api/organizations/{id}/process/rounds", ORG_ID)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "%s",
                                  "title": "Value proposition & onboarding",
                                  "name": "MVP test turu",
                                  "stage": "MVP",
                                  "startsOn": "2026-08-02",
                                  "endsOn": "2026-08-31"
                                }
                                """.formatted(ACTOR_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rounds[0].id").value(ROUND_ID.toString()));
    }

    @Test
    void submitsAnAssignment() throws Exception {
        when(startupProcessService.submitAssignment(eq(ORG_ID), eq(ASSIGNMENT_ID),
                any(SubmitProcessAssignmentRequest.class)))
                .thenReturn(process(StartupStage.MVP));

        mockMvc.perform(put("/api/organizations/{id}/process/assignments/{assignmentId}/submission",
                        ORG_ID, ASSIGNMENT_ID)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "%s",
                                  "documentId": "30000000-0000-0000-0000-000000000001"
                                }
                                """.formatted(ACTOR_ID)))
                .andExpect(status().isOk());
    }

    private static UsernamePasswordAuthenticationToken signedInAs(UUID userId) {
        return new UsernamePasswordAuthenticationToken(
                new AppUserPrincipal(userId, "founder@dongu.co", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid"),
                null, List.of());
    }

    @Test
    void actsAsTheSignedInUserWhateverActorIdIsSent() throws Exception {
        UUID signedIn = UUID.fromString("20000000-0000-0000-0000-000000000099");
        when(startupProcessService.updateStage(eq(ORG_ID), any(UpdateStartupStageRequest.class)))
                .thenReturn(process(StartupStage.MVP));

        mockMvc.perform(put("/api/organizations/{id}/process/stage", ORG_ID)
                        .with(authentication(signedInAs(signedIn)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "actorId": "%s", "stage": "MVP" }
                                """.formatted(ACTOR_ID)))
                .andExpect(status().isOk());

        verify(startupProcessService).updateStage(ORG_ID, new UpdateStartupStageRequest(signedIn, StartupStage.MVP));
    }
}
