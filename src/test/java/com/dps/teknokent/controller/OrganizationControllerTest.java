package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.dto.responses.OrganizationResponse;
import com.dps.teknokent.service.OrganizationService;

@WebMvcTest(OrganizationController.class)
@Import(SecurityConfig.class)
class OrganizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    @Test
    void requiresAuthenticationForOrganizations() throws Exception {
        mockMvc.perform(get("/api/organizations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsOrganizations() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000111");

        when(organizationService.findAll()).thenReturn(List.of(new OrganizationResponse(
                organizationId,
                "Teknokent",
                "Teknoloji girişimciliği için destek merkezi",
                Instant.parse("2026-08-20T08:00:00Z"),
                Instant.parse("2026-08-20T08:05:00Z")
        )));

        mockMvc.perform(get("/api/organizations").with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(organizationId.toString()))
                .andExpect(jsonPath("$[0].name").value("Teknokent"))
                .andExpect(jsonPath("$[0].description").value("Teknoloji girişimciliği için destek merkezi"));
    }

    @Test
    void returnsOrganizationById() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000222");

        when(organizationService.findById(organizationId)).thenReturn(new OrganizationResponse(
                organizationId,
                "İnovasyon A.Ş.",
                "Yazılım ve teknoloji girişimlerine destek veren kuruluş",
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T09:10:00Z")
        ));

        mockMvc.perform(get("/api/organizations/{id}", organizationId).with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(organizationId.toString()))
                .andExpect(jsonPath("$.name").value("İnovasyon A.Ş."))
                .andExpect(jsonPath("$.description").value("Yazılım ve teknoloji girişimlerine destek veren kuruluş"));
    }

    @Test
    void createsOrganization() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000333");

        when(organizationService.create(any(CreateOrganizationRequest.class))).thenReturn(new OrganizationResponse(
                organizationId,
                "Girişim Merkezi",
                "İş fikirlerini hayata geçiren startup topluluğu",
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:02:00Z")
        ));

        mockMvc.perform(post("/api/organizations")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Girişim Merkezi",
                                  "description": "İş fikirlerini hayata geçiren startup topluluğu"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(organizationId.toString()))
                .andExpect(jsonPath("$.name").value("Girişim Merkezi"))
                .andExpect(jsonPath("$.description").value("İş fikirlerini hayata geçiren startup topluluğu"));
    }

    @Test
    void rejectsCreateOrganizationWhenNameIsBlank() throws Exception {
        mockMvc.perform(post("/api/organizations")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "description": "Geçersiz isim"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
