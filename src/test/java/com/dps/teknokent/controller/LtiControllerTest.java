package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
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
import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.responses.LtiLaunchResponse;
import com.dps.teknokent.dto.responses.OfficeHoursAvailabilityResponse;
import com.dps.teknokent.dto.responses.LtiLoginResponse;
import com.dps.teknokent.dto.responses.OfficeHoursCourseResponse;
import com.dps.teknokent.security.LtiToolKeys;
import com.dps.teknokent.service.LtiLaunchService;
import com.dps.teknokent.service.OfficeHoursAvailabilityService;
import com.dps.teknokent.service.OfficeHoursCourseService;

@WebMvcTest({ LtiController.class, OfficeHoursController.class })
@Import(SecurityConfig.class)
class LtiControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID COURSE_ID = UUID.fromString("00000000-0000-0000-0000-00000000c002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LtiLaunchService launchService;
    @MockitoBean
    private LtiToolKeys toolKeys;
    @MockitoBean
    private OfficeHoursCourseService courseService;
    @MockitoBean
    private OfficeHoursAvailabilityService availabilityService;

    /** The frontend's server: Spring's Basic user, which has no roles at all. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor frontendServer() {
        return user("frontend").authorities(List.of());
    }

    /** A Menton account signed in with Firebase, which must not reach office-hours data. */
    private static UsernamePasswordAuthenticationToken mentonMentee() {
        return new UsernamePasswordAuthenticationToken("someone", null,
                List.of(new SimpleGrantedAuthority("ROLE_MENTEE")));
    }

    @Test
    void passesTheLoginInitiationOnForTheFrontendServer() throws Exception {
        when(launchService.startLogin(any())).thenReturn(new LtiLoginResponse("http://moodle/auth?state=s"));

        mockMvc.perform(post("/api/lti/login-initiations").with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"http://moodle\",\"loginHint\":\"2\",\"targetLinkUri\":\"http://x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value("http://moodle/auth?state=s"));
    }

    @Test
    void asksForTheIssuerAndLoginHint() throws Exception {
        mockMvc.perform(post("/api/lti/login-initiations").with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetLinkUri\":\"http://x\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(launchService);
    }

    @Test
    void completesTheLaunchForTheFrontendServer() throws Exception {
        when(launchService.completeLaunch(any())).thenReturn(new LtiLaunchResponse(USER_ID, "Ayşe Yılmaz", COURSE_ID,
                "Introduction to Computing", OfficeHoursRole.STUDENT));

        mockMvc.perform(post("/api/lti/launches").with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"t\",\"state\":\"s\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.role").value("STUDENT"));
    }

    @Test
    void keepsLtiAndOfficeHoursAwayFromMentonAccounts() throws Exception {
        mockMvc.perform(post("/api/lti/launches").with(authentication(mentonMentee()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"t\",\"state\":\"s\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/office-hours/users/{userId}/courses/{courseId}", USER_ID, COURSE_ID)
                        .with(authentication(mentonMentee())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(launchService, courseService);
    }

    @Test
    void refusesAnonymousCalls() throws Exception {
        mockMvc.perform(post("/api/lti/launches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"t\",\"state\":\"s\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheToolKeySetToAnyone() throws Exception {
        when(toolKeys.publicKeySet()).thenReturn(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "k1"))));

        mockMvc.perform(get("/api/lti/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("k1"));
    }

    @Test
    void showsACourseToOneOfItsPeople() throws Exception {
        when(courseService.courseFor(USER_ID, COURSE_ID)).thenReturn(new OfficeHoursCourseResponse(COURSE_ID,
                "Introduction to Computing", "CENG 101", OfficeHoursRole.INSTRUCTOR, "Prof. Ali Kaya"));

        mockMvc.perform(get("/api/office-hours/users/{userId}/courses/{courseId}", USER_ID, COURSE_ID)
                        .with(frontendServer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Introduction to Computing"))
                .andExpect(jsonPath("$.role").value("INSTRUCTOR"));
    }

    @Test
    void readsAndSavesAnAcademicsOfficeHours() throws Exception {
        OfficeHoursAvailabilityResponse saved = new OfficeHoursAvailabilityResponse(List.of(
                new OfficeHoursAvailabilityResponse.Rule(UUID.randomUUID(), DayOfWeek.TUESDAY, LocalTime.of(14, 0),
                        LocalTime.of(16, 0), 30)), List.of(), null, 45, 15);
        when(availabilityService.find(USER_ID)).thenReturn(saved);
        when(availabilityService.update(eq(USER_ID), any())).thenReturn(saved);

        mockMvc.perform(get("/api/office-hours/users/{userId}/availability", USER_ID)
                        .with(frontendServer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].dayOfWeek").value("TUESDAY"))
                .andExpect(jsonPath("$.rules[0].slotMinutes").value(30));
        mockMvc.perform(put("/api/office-hours/users/{userId}/availability", USER_ID)
                        .with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[{"dayOfWeek":"TUESDAY","startTime":"14:00","endTime":"16:00","slotMinutes":30}],
                                 "exceptions":[],"defaultMeetingDurationMinutes":45,"meetingBufferMinutes":15}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMeetingDurationMinutes").value(45));
    }

    @Test
    void refusesOfficeHoursOutsideTheLimits() throws Exception {
        mockMvc.perform(put("/api/office-hours/users/{userId}/availability", USER_ID)
                        .with(frontendServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules":[],"exceptions":[],"defaultMeetingDurationMinutes":5,"meetingBufferMinutes":15}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(availabilityService);
    }
}
