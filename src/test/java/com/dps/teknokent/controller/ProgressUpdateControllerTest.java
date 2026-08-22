package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.dps.teknokent.domain.ProgressStatus;
import com.dps.teknokent.dto.requests.CreateProgressUpdateRequest;
import com.dps.teknokent.dto.responses.ProgressUpdateResponse;
import com.dps.teknokent.service.ProgressUpdateService;

@WebMvcTest(ProgressUpdateController.class)
@Import(SecurityConfig.class)
class ProgressUpdateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProgressUpdateService progressUpdateService;

    @Test
    void requiresAuthenticationForList() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        mockMvc.perform(get("/api/organizations/{organizationId}/progress", organizationId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requiresAuthenticationForCreate() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        mockMvc.perform(post("/api/organizations/{organizationId}/progress", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorId": "00000000-0000-0000-0000-000000000101",
                                  "category": "Product",
                                  "status": "ON_TRACK",
                                  "summary": "Sprint hedefleri doğrultusunda ilerleme kaydedildi.",
                                  "evidence": "Kullanıcı geri bildirimleri ve demo çıktıları",
                                  "recordedAt": "2026-08-15"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsProgressUpdatesForOrganization() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID authorId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID updateId = UUID.fromString("00000000-0000-0000-0000-000000000401");

        when(progressUpdateService.findByOrganization(organizationId)).thenReturn(List.of(new ProgressUpdateResponse(
                updateId,
                organizationId,
                authorId,
                "Ayşe Yılmaz",
                "Product",
                ProgressStatus.ON_TRACK,
                "Sprint hedefleri doğrultusunda ilerleme kaydedildi.",
                "Kullanıcı geri bildirimleri ve demo çıktıları",
                LocalDate.of(2026, 8, 15),
                Instant.parse("2026-08-15T10:30:00Z")
        )));

        mockMvc.perform(get("/api/organizations/{organizationId}/progress", organizationId).with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(updateId.toString()))
                .andExpect(jsonPath("$[0].organizationId").value(organizationId.toString()))
                .andExpect(jsonPath("$[0].authorId").value(authorId.toString()))
                .andExpect(jsonPath("$[0].authorName").value("Ayşe Yılmaz"))
                .andExpect(jsonPath("$[0].category").value("Product"))
                .andExpect(jsonPath("$[0].status").value("ON_TRACK"));
    }

    @Test
    void createsProgressUpdate() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID authorId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID updateId = UUID.fromString("00000000-0000-0000-0000-000000000402");

        when(progressUpdateService.create(any(UUID.class), any(CreateProgressUpdateRequest.class))).thenReturn(new ProgressUpdateResponse(
                updateId,
                organizationId,
                authorId,
                "Ayşe Yılmaz",
                "Product",
                ProgressStatus.AT_RISK,
                "İkinci sprintte iş akışında gecikme var.",
                "Yapılacaklar listesinde 2 kritik görev bekliyor",
                LocalDate.of(2026, 8, 18),
                Instant.parse("2026-08-18T11:00:00Z")
        ));

        mockMvc.perform(post("/api/organizations/{organizationId}/progress", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorId": "00000000-0000-0000-0000-000000000101",
                                  "category": "Product",
                                  "status": "AT_RISK",
                                  "summary": "İkinci sprintte iş akışında gecikme var.",
                                  "evidence": "Yapılacaklar listesinde 2 kritik görev bekliyor",
                                  "recordedAt": "2026-08-18"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(updateId.toString()))
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
                .andExpect(jsonPath("$.authorId").value(authorId.toString()))
                .andExpect(jsonPath("$.category").value("Product"))
                .andExpect(jsonPath("$.status").value("AT_RISK"));
    }

    @Test
    void rejectsCreateProgressUpdateWhenRequiredFieldsAreMissing() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        mockMvc.perform(post("/api/organizations/{organizationId}/progress", organizationId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorId": "00000000-0000-0000-0000-000000000101",
                                  "category": "",
                                  "status": "ON_TRACK",
                                  "summary": "",
                                  "recordedAt": "2026-08-15"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
