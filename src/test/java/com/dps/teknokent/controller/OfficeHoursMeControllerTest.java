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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.dto.requests.AnswerOfficeHoursRequest;
import com.dps.teknokent.dto.requests.UpdateOfficeHoursProfileRequest;
import com.dps.teknokent.dto.responses.OfficeHoursProfileResponse;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.service.OfficeHoursMeService;

@WebMvcTest(OfficeHoursMeController.class)
@Import(SecurityConfig.class)
class OfficeHoursMeControllerTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-00000000d002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OfficeHoursMeService meService;

    /** The frontend's server: Spring's Basic user, given ROLE_SERVICE by spring.security.user.roles. */
    private static RequestPostProcessor frontendServer() {
        return user("frontend").roles("SERVICE");
    }

    @Test
    void answersForTheAccountTheFrontendServerNames() throws Exception {
        when(meService.profile(ACTOR)).thenReturn(new OfficeHoursProfileResponse(true, "A-204", null, null));

        mockMvc.perform(get("/api/office-hours/me/profile").param("actorId", ACTOR.toString())
                        .with(frontendServer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listed").value(true))
                .andExpect(jsonPath("$.defaultPlace").value("A-204"));
    }

    @Test
    void keepsTheRoutesAwayFromSignedInMentonAccountsAndAnonymousCallers() throws Exception {
        UsernamePasswordAuthenticationToken academician = new UsernamePasswordAuthenticationToken("someone", null,
                List.of(new SimpleGrantedAuthority("ROLE_ACADEMICIAN")));
        mockMvc.perform(get("/api/office-hours/me/inbox").param("actorId", ACTOR.toString())
                        .with(authentication(academician)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/office-hours/me/inbox").param("actorId", ACTOR.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(meService);
    }

    @Test
    void saysNotFoundForSomeoneOdtuDoesNotBack() throws Exception {
        when(meService.inbox(ACTOR)).thenThrow(new ResourceNotFoundException("Office hours not found."));

        mockMvc.perform(get("/api/office-hours/me/inbox").param("actorId", ACTOR.toString())
                        .with(frontendServer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesAnOnlineLinkThatIsNotAWebAddress() throws Exception {
        mockMvc.perform(put("/api/office-hours/me/profile").param("actorId", ACTOR.toString())
                        .with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listed\":true,\"onlineLink\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(meService);
    }

    @Test
    void savesTheProfile() throws Exception {
        when(meService.saveProfile(eq(ACTOR), any()))
                .thenReturn(new OfficeHoursProfileResponse(true, null, "https://meet.example/x", null));

        mockMvc.perform(put("/api/office-hours/me/profile").param("actorId", ACTOR.toString())
                        .with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listed\":true,\"onlineLink\":\"https://meet.example/x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlineLink").value("https://meet.example/x"));
        verify(meService).saveProfile(ACTOR,
                new UpdateOfficeHoursProfileRequest(true, null, "https://meet.example/x", null));
    }

    @Test
    void refusesMoreThanThreeOfferedTimes() throws Exception {
        String time = "\"2026-11-24T11:00:00Z\"";
        mockMvc.perform(post("/api/office-hours/me/requests/{id}/decision", REQUEST).param("actorId",
                        ACTOR.toString())
                        .with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PROPOSE_TIMES\",\"times\":[" + String.join(",", time, time, time, time)
                                + "]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(meService);
    }

    @Test
    void passesTheAnswerOn() throws Exception {
        mockMvc.perform(post("/api/office-hours/me/requests/{id}/decision", REQUEST).param("actorId",
                        ACTOR.toString())
                        .with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT\",\"time\":\"2026-11-24T11:00:00Z\"}"))
                .andExpect(status().isOk());
        verify(meService).answer(ACTOR, REQUEST, new AnswerOfficeHoursRequest(OfficeHoursRequestAction.ACCEPT,
                Instant.parse("2026-11-24T11:00:00Z"), null, null));
    }
}
