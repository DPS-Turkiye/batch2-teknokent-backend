package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.RegisterFounderRequest;
import com.dps.teknokent.dto.requests.RegisterMemberRequest;
import com.dps.teknokent.dto.requests.RegisterMentorRequest;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.service.RegistrationService;

@WebMvcTest(AuthRegistrationController.class)
@Import(SecurityConfig.class)
class AuthRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationService registrationService;

    @Test
    void requiresAuthenticationForFounderRegister() throws Exception {
        mockMvc.perform(post("/api/auth/register/founder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ayşe Demir",
                                  "email": "ayse@startup.com",
                                  "organizationName": "GreenLoop"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registersFounder() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000811");
        UUID organizationId = UUID.fromString("10000000-0000-0000-0000-000000000011");

        when(registrationService.registerFounder(any(RegisterFounderRequest.class))).thenReturn(new UserResponse(
                userId,
                "ayse@startup.com",
                "Ayşe Demir",
                UserRole.MENTEE,
                UserAccountStatus.PENDING,
                organizationId,
                "GreenLoop",
                "Kurucu",
                null
        ));

        mockMvc.perform(post("/api/auth/register/founder")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ayşe Demir",
                                  "email": "ayse@startup.com",
                                  "organizationName": "GreenLoop",
                                  "jobTitle": "Kurucu"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("ayse@startup.com"))
                .andExpect(jsonPath("$.role").value("MENTEE"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()));
    }

    @Test
    void registersMentor() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000812");

        when(registrationService.registerMentor(any(RegisterMentorRequest.class))).thenReturn(new UserResponse(
                userId,
                "kemal@mentor.com",
                "Dr. Kemal Arslan",
                UserRole.MENTOR,
                UserAccountStatus.PENDING,
                null,
                null,
                null,
                null
        ));

        mockMvc.perform(post("/api/auth/register/mentor")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Dr. Kemal Arslan",
                                  "email": "kemal@mentor.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("MENTOR"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void registersMember() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000813");
        UUID organizationId = UUID.fromString("10000000-0000-0000-0000-000000000011");

        when(registrationService.registerMember(any(RegisterMemberRequest.class))).thenReturn(new UserResponse(
                userId,
                "elif@startup.com",
                "Elif Demir",
                UserRole.MENTEE,
                UserAccountStatus.APPROVED,
                organizationId,
                "GreenLoop",
                null,
                null
        ));

        mockMvc.perform(post("/api/auth/register/member")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Elif Demir",
                                  "email": "elif@startup.com",
                                  "inviteCode": "ABCD1234"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()));
    }

    @Test
    void rejectsFounderRegisterWhenEmailIsBlank() throws Exception {
        mockMvc.perform(post("/api/auth/register/founder")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ayşe Demir",
                                  "email": "",
                                  "organizationName": "GreenLoop"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFounderRegisterWhenPhoneContainsLetters() throws Exception {
        mockMvc.perform(post("/api/auth/register/founder")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ayşe Demir",
                                  "email": "ayse@startup.com",
                                  "organizationName": "GreenLoop",
                                  "phone": "+90abc5551112233"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
    }
}
