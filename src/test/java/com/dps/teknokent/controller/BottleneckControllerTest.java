package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.dto.requests.CreateBottleneckRequest;
import com.dps.teknokent.dto.requests.UpdateBottleneckRequest;
import com.dps.teknokent.dto.responses.BottleneckResponse;
import com.dps.teknokent.service.BottleneckService;

@WebMvcTest(BottleneckController.class)
@Import(SecurityConfig.class)
class BottleneckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BottleneckService bottleneckService;

    @Test
    void requiresAuthenticationForOrganizationBottlenecks() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(get("/api/organizations/{organizationId}/bottlenecks", organizationId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsBottlenecksForOrganization() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bottleneckId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(bottleneckService.findByOrganization(organizationId)).thenReturn(List.of(new BottleneckResponse(
                bottleneckId,
                organizationId,
                ownerId,
                "Ahmet Yılmaz",
                "Mentor onboarding delay",
                "Mentor assignment is delayed",
                BottleneckSeverity.HIGH,
                BottleneckStatus.OPEN,
                LocalDate.of(2026, 9, 15),
                null,
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z")
        )));

        mockMvc.perform(get("/api/organizations/{organizationId}/bottlenecks", organizationId)
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(bottleneckId.toString()))
                .andExpect(jsonPath("$[0].title").value("Mentor onboarding delay"))
                .andExpect(jsonPath("$[0].severity").value("HIGH"));
    }

    @Test
    void createsBottleneck() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bottleneckId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(bottleneckService.create(eq(organizationId), any(CreateBottleneckRequest.class))).thenReturn(new BottleneckResponse(
                bottleneckId,
                organizationId,
                ownerId,
                "Ahmet Yılmaz",
                "Funding process stuck",
                "Funding approval is waiting on legal review",
                BottleneckSeverity.CRITICAL,
                BottleneckStatus.OPEN,
                LocalDate.of(2026, 9, 20),
                null,
                Instant.parse("2026-08-20T10:15:00Z"),
                Instant.parse("2026-08-20T10:15:00Z")
        ));

        mockMvc.perform(post("/api/organizations/{organizationId}/bottlenecks", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "00000000-0000-0000-0000-000000000002",
                                  "title": "Funding process stuck",
                                  "description": "Funding approval is waiting on legal review",
                                  "severity": "CRITICAL",
                                  "targetResolutionDate": "2026-09-20"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(bottleneckId.toString()))
                .andExpect(jsonPath("$.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.title").value("Funding process stuck"));
    }

    @Test
    void rejectsCreateBottleneckWhenTitleIsBlank() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(post("/api/organizations/{organizationId}/bottlenecks", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "00000000-0000-0000-0000-000000000002",
                                  "title": "",
                                  "description": "Missing title",
                                  "severity": "MEDIUM",
                                  "targetResolutionDate": "2026-09-10"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesBottleneck() throws Exception {
        UUID bottleneckId = UUID.fromString("00000000-0000-0000-0000-000000000013");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(bottleneckService.update(eq(bottleneckId), any(UpdateBottleneckRequest.class))).thenReturn(new BottleneckResponse(
                bottleneckId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                ownerId,
                "Ahmet Yılmaz",
                "Legal review resolved",
                "Approval issue has been resolved",
                BottleneckSeverity.MEDIUM,
                BottleneckStatus.RESOLVED,
                LocalDate.of(2026, 9, 30),
                "Closed after legal review",
                Instant.parse("2026-08-20T11:00:00Z"),
                Instant.parse("2026-08-20T11:05:00Z")
        ));

        mockMvc.perform(patch("/api/bottlenecks/{id}", bottleneckId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "00000000-0000-0000-0000-000000000002",
                                  "title": "Legal review resolved",
                                  "description": "Approval issue has been resolved",
                                  "severity": "MEDIUM",
                                  "status": "RESOLVED",
                                  "targetResolutionDate": "2026-09-30",
                                  "resolutionNotes": "Closed after legal review"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bottleneckId.toString()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNotes").value("Closed after legal review"));
    }
}
