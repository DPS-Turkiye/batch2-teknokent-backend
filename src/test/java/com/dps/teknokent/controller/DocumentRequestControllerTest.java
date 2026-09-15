package com.dps.teknokent.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import com.dps.teknokent.service.DocumentRequestService;

@WebMvcTest(DocumentRequestController.class)
@Import(SecurityConfig.class)
class DocumentRequestControllerTest {

    private static final UUID PROGRAM_ID = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000009");
    private static final UUID ITEM_ID = UUID.fromString("80000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentRequestService documentRequestService;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/document-requests")
                        .param("programId", PROGRAM_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\": \"" + ACTOR_ID + "\", \"titles\": [\"CV\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replacesAProgramsDocumentRequestsWithADueDate() throws Exception {
        LocalDate dueDate = LocalDate.parse("2026-09-30");
        when(documentRequestService.replaceOnProgram(PROGRAM_ID, ACTOR_ID, List.of("Finansal tablolar"), dueDate))
                .thenReturn(List.of(new DocumentRequestItemResponse(ITEM_ID, "Finansal tablolar", 0, dueDate, ORG_ID,
                        null, null, null, PROGRAM_ID, ACTOR_ID, "Ece Koordinatör",
                        Instant.parse("2026-09-14T08:00:00Z"), List.of())));

        mockMvc.perform(put("/api/document-requests")
                        .param("programId", PROGRAM_ID.toString())
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "20000000-0000-0000-0000-000000000009",
                                  "titles": ["Finansal tablolar"],
                                  "dueDate": "2026-09-30"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$[0].dueDate").value("2026-09-30"))
                .andExpect(jsonPath("$[0].createdByName").value("Ece Koordinatör"));
    }

    @Test
    void rejectsAReplaceWithoutATarget() throws Exception {
        mockMvc.perform(put("/api/document-requests")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\": \"" + ACTOR_ID + "\", \"titles\": [\"CV\"]}"))
                .andExpect(status().isBadRequest());
    }
}
