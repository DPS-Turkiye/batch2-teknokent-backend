package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.MentorshipTaskService;

@WebMvcTest(MentorshipTaskController.class)
@Import(SecurityConfig.class)
class MentorshipTaskControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private MentorshipTaskService tasks;

    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID MENTOR = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final String URL = "/api/mentorship-requests/" + REQUEST;

    @Test
    void bothEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get(URL + "/task-assignees")).andExpect(status().isUnauthorized());
        mvc.perform(post(URL + "/tasks").contentType(MediaType.APPLICATION_JSON).content(body("Task")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks);
    }

    @Test
    void serviceCredentialsMustNameTheActor() throws Exception {
        mvc.perform(get(URL + "/task-assignees").with(user("service"))).andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }

    @Test
    void theProxyActorScopesBothAssigneeReadsAndCreation() throws Exception {
        when(tasks.assignees(MENTOR, REQUEST)).thenReturn(List.of());
        mvc.perform(get(URL + "/task-assignees").param("actorId", MENTOR.toString()).with(user("service")))
                .andExpect(status().isOk());
        mvc.perform(post(URL + "/tasks").param("actorId", MENTOR.toString()).with(user("service"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("Task")))
                .andExpect(status().isCreated());
        verify(tasks).assignees(MENTOR, REQUEST);
        verify(tasks).assign(eq(MENTOR), eq(REQUEST), any());
    }

    @Test
    void aVerifiedPrincipalCannotSpoofTheActorQueryParameter() throws Exception {
        var principal = new AppUserPrincipal(MENTOR, "mentor@task.test", UserRole.MENTOR,
                UserAccountStatus.APPROVED, "task-mentor");
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_MENTOR")));
        mvc.perform(post(URL + "/tasks").param("actorId", UUID.randomUUID().toString())
                        .with(authentication(auth)).contentType(MediaType.APPLICATION_JSON).content(body("Task")))
                .andExpect(status().isCreated());
        verify(tasks).assign(eq(MENTOR), eq(REQUEST), any());
    }

    @Test
    void emptyAndOverlongTitlesAreRejectedBeforeTheService() throws Exception {
        for (String title : List.of("   ", "x".repeat(256))) {
            mvc.perform(post(URL + "/tasks").param("actorId", MENTOR.toString()).with(user("service"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(title)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(tasks);
    }

    private String body(String title) {
        return "{\"ownerId\":\"" + OWNER + "\",\"title\":\"" + title + "\"}";
    }
}
