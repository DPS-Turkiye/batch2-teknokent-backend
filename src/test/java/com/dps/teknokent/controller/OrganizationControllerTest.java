package com.dps.teknokent.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import com.dps.teknokent.security.AppUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationStatusRequest;
import com.dps.teknokent.dto.responses.OrganizationInviteResponse;
import com.dps.teknokent.dto.responses.OrganizationMemberResponse;
import com.dps.teknokent.dto.responses.OrganizationResponse;
import com.dps.teknokent.service.AppUserService;
import com.dps.teknokent.service.OrganizationService;

@WebMvcTest(OrganizationController.class)
@Import(SecurityConfig.class)
class OrganizationControllerTest {

    private static OrganizationResponse organizationResponse(UUID id, String name, String description,
            Instant createdAt, Instant updatedAt) {
        return new OrganizationResponse(id, name, description, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null, createdAt, updatedAt);
    }

    private static OrganizationMemberResponse memberResponse(UUID id, UUID organizationId, String fullName,
            String jobTitle, boolean founder) {
        return new OrganizationMemberResponse(id, "ekip@greenloop.demo", fullName, UserRole.MENTEE,
                UserAccountStatus.APPROVED, organizationId, "GreenLoop", jobTitle, founder,
                Instant.parse("2026-06-12T09:00:00Z"));
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean
    private AppUserService appUserService;

    @Test
    void requiresAuthenticationForOrganizations() throws Exception {
        mockMvc.perform(get("/api/organizations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsOrganizations() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000111");

        when(organizationService.findAll()).thenReturn(List.of(organizationResponse(
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

        when(organizationService.findById(organizationId)).thenReturn(organizationResponse(
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

        when(organizationService.create(any(CreateOrganizationRequest.class))).thenReturn(organizationResponse(
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

    @Test
    void returnsOrganizationMembers() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000444");
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000555");

        when(appUserService.findByOrganization(organizationId)).thenReturn(List.of(
                memberResponse(memberId, organizationId, "Ekip Üyesi", "Ürün Müdürü", true)));

        mockMvc.perform(get("/api/organizations/{id}/members", organizationId).with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(memberId.toString()))
                .andExpect(jsonPath("$[0].fullName").value("Ekip Üyesi"))
                .andExpect(jsonPath("$[0].founder").value(true))
                .andExpect(jsonPath("$[0].joinedAt").value("2026-06-12T09:00:00Z"));
    }

    @Test
    void updatesStartupStatus() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000444");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        OrganizationResponse updated = new OrganizationResponse(organizationId, "Döngü", null, null, null, null,
                null, null, null, "MVP yayında", null, List.of(), "v0.9.2", "Öğretmen modülü geliştiriliyor.", 72,
                null, null, Instant.parse("2026-08-20T09:00:00Z"), Instant.parse("2026-09-11T09:00:00Z"));
        when(organizationService.updateStatus(eq(organizationId), any(UpdateOrganizationStatusRequest.class)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/organizations/{id}/status", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "%s",
                                  "statusVersion": "v0.9.2",
                                  "statusSummary": "Öğretmen modülü geliştiriliyor.",
                                  "completionPercent": 72
                                }
                                """.formatted(actorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusVersion").value("v0.9.2"))
                .andExpect(jsonPath("$.completionPercent").value(72));
    }

    @Test
    void rejectsACompletionPercentAboveOneHundred() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000444");

        mockMvc.perform(put("/api/organizations/{id}/status", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "00000000-0000-0000-0000-000000000003",
                                  "completionPercent": 140
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(organizationService);
    }

    @Test
    void updatesATeamMember() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000444");
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000555");
        when(appUserService.updateOrganizationMember(eq(organizationId), eq(memberId),
                any(UpdateOrganizationMemberRequest.class)))
                .thenReturn(memberResponse(memberId, organizationId, "Sude Arya", "Developer", false));

        mockMvc.perform(patch("/api/organizations/{id}/members/{memberId}", organizationId, memberId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "00000000-0000-0000-0000-000000000003",
                                  "fullName": "Sude Arya",
                                  "jobTitle": "Developer"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobTitle").value("Developer"));
    }

    @Test
    void removesATeamMember() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000444");
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000555");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        mockMvc.perform(delete("/api/organizations/{id}/members/{memberId}", organizationId, memberId)
                        .param("actorId", actorId.toString())
                        .with(user("test-user")))
                .andExpect(status().isNoContent());

        verify(appUserService).removeOrganizationMember(organizationId, memberId, actorId);
    }

    @Test
    void addsOrganizationMember() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000666");
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000777");

        when(appUserService.addOrganizationMember(eq(organizationId), any(CreateOrganizationMemberRequest.class)))
                .thenReturn(memberResponse(memberId, organizationId, "Yeni Üye", "Ürün Yöneticisi", false));

        mockMvc.perform(post("/api/organizations/{id}/members", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Yeni Üye",
                                  "email": "yeni.uye@greenloop.demo",
                                  "jobTitle": "Ürün Yöneticisi"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(memberId.toString()))
                .andExpect(jsonPath("$.fullName").value("Yeni Üye"));
    }

    @Test
    void rejectsAddingMemberWithBlankEmail() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000888");

        mockMvc.perform(post("/api/organizations/{id}/members", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Yeni Üye",
                                  "email": "",
                                  "jobTitle": "Ürün Yöneticisi"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthenticationForInviteCode() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(get("/api/organizations/{id}/invite-code", organizationId)
                        .param("actorId", actorId.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsInviteCode() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        when(organizationService.getInviteCode(organizationId, actorId))
                .thenReturn(new OrganizationInviteResponse(organizationId, "ABCD2345"));

        mockMvc.perform(get("/api/organizations/{id}/invite-code", organizationId)
                        .param("actorId", actorId.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
                .andExpect(jsonPath("$.inviteCode").value("ABCD2345"));
    }

    @Test
    void regeneratesInviteCode() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        when(organizationService.regenerateInviteCode(eq(organizationId), eq(actorId)))
                .thenReturn(new OrganizationInviteResponse(organizationId, "NEWCODE8"));

        mockMvc.perform(post("/api/organizations/{id}/invite-code", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "%s"
                                }
                                """.formatted(actorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").value("NEWCODE8"));
    }

    private static UsernamePasswordAuthenticationToken signedInAs(UUID userId) {
        return new UsernamePasswordAuthenticationToken(
                new AppUserPrincipal(userId, "founder@dongu.co", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid"),
                null, List.of());
    }

    @Test
    void removesAMemberAsTheSignedInUserWhateverActorIdIsSent() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000444");
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000555");
        UUID signedIn = UUID.fromString("00000000-0000-0000-0000-000000000003");

        mockMvc.perform(delete("/api/organizations/{id}/members/{memberId}", organizationId, memberId)
                        .param("actorId", "00000000-0000-0000-0000-000000000999")
                        .with(authentication(signedInAs(signedIn))))
                .andExpect(status().isNoContent());

        verify(appUserService).removeOrganizationMember(organizationId, memberId, signedIn);
    }
}
