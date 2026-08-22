package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.service.AppUserService;

@WebMvcTest(AppUserController.class)
@Import(SecurityConfig.class)
class AppUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserService appUserService;

    @Test
    void requiresAuthenticationForUsersList() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsUsersFilteredByRole() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(appUserService.findAll(UserRole.MENTOR)).thenReturn(List.of(new UserResponse(
                userId,
                "mentor@teknokent.demo",
                "Dr. Kemal Arslan",
                UserRole.MENTOR,
                organizationId,
                "Teknokent",
                "Ürün & Araştırma Direktörü"
        )));

        mockMvc.perform(get("/api/users").param("role", "MENTOR").with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(userId.toString()))
                .andExpect(jsonPath("$[0].email").value("mentor@teknokent.demo"))
                .andExpect(jsonPath("$[0].role").value("MENTOR"));
    }

    @Test
    void returnsUserById() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(appUserService.findById(userId)).thenReturn(new UserResponse(
                userId,
                "mentee@teknokent.demo",
                "Elif Demir",
                UserRole.MENTEE,
                organizationId,
                "Teknokent",
                "Product Designer"
        ));

        mockMvc.perform(get("/api/users/{id}", userId).with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.fullName").value("Elif Demir"))
                .andExpect(jsonPath("$.role").value("MENTEE"));
    }

    @Test
    void returnsUsersWithoutRoleFilter() throws Exception {
        UUID firstUserId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        UUID secondUserId = UUID.fromString("00000000-0000-0000-0000-000000000104");

        when(appUserService.findAll(any())).thenReturn(List.of(
                new UserResponse(
                        firstUserId,
                        "admin@teknokent.demo",
                        "Admin User",
                        UserRole.ADMIN,
                        null,
                        null,
                        "Platform Admin"
                ),
                new UserResponse(
                        secondUserId,
                        "mentor@teknokent.demo",
                        "Mentor User",
                        UserRole.MENTOR,
                        null,
                        null,
                        "Mentor"
                )
        ));

        mockMvc.perform(get("/api/users").with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("admin@teknokent.demo"))
                .andExpect(jsonPath("$[1].role").value("MENTOR"));
    }
}
