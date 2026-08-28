package com.dps.teknokent.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.AppUserService;

@WebMvcTest(AuthMeController.class)
@Import(SecurityConfig.class)
class AuthMeControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000811");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserService appUserService;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMachineCredentialsWithoutAppUser() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(user("test-user")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsSignedInUser() throws Exception {
        UUID organizationId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        AppUserPrincipal principal = new AppUserPrincipal(
                USER_ID, "ayse@startup.com", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid-1");

        when(appUserService.findById(USER_ID)).thenReturn(new UserResponse(
                USER_ID,
                "ayse@startup.com",
                "Ayşe Demir",
                UserRole.MENTEE,
                UserAccountStatus.APPROVED,
                organizationId,
                "GreenLoop",
                "Kurucu"
        ));

        mockMvc.perform(get("/api/auth/me")
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                                principal, "firebase", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("ayse@startup.com"))
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()));
    }
}
