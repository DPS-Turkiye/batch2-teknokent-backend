package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.dps.teknokent.dto.requests.ShareDocumentRequest;
import com.dps.teknokent.dto.responses.DocumentDownloadResponse;
import com.dps.teknokent.dto.responses.DocumentShareResponse;
import com.dps.teknokent.dto.responses.OrganizationDocumentResponse;
import com.dps.teknokent.service.StartupDocumentService;
import com.dps.teknokent.service.DocumentRequestService;
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import java.time.LocalDate;

@WebMvcTest(StartupDocumentController.class)
@Import(SecurityConfig.class)
class StartupDocumentControllerTest {

    private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MENTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID DOCUMENT_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StartupDocumentService startupDocumentService;

    @MockitoBean
    private DocumentRequestService documentRequestService;

    private static OrganizationDocumentResponse deck() {
        return new OrganizationDocumentResponse(DOCUMENT_ID, "pitch-deck", "Pitch Deck v2.pdf", "application/pdf",
                4_200_000, Instant.parse("2026-09-12T08:00:00Z"), ACTOR_ID, "Gülse Buran", null, null, null, null, null, null,
                List.of(new DocumentShareResponse(MENTOR_ID, "Nihan Öztürk", Instant.parse("2026-09-05T10:00:00Z"))));
    }

    @Test
    void listsTheStartupsDocumentRequestsWithTheirDueDateAndRequester() throws Exception {
        UUID itemId = UUID.fromString("80000000-0000-0000-0000-000000000001");
        when(documentRequestService.listForOrganization(ORG_ID, ACTOR_ID)).thenReturn(List.of(
                new DocumentRequestItemResponse(itemId, "Finansal tablolar", 0, LocalDate.parse("2026-09-30"), ORG_ID,
                        null, null, null, null, MENTOR_ID, "Nihan Öztürk", Instant.parse("2026-09-14T08:00:00Z"),
                        List.of())));

        mockMvc.perform(get("/api/organizations/{id}/document-requests", ORG_ID)
                        .param("actorId", ACTOR_ID.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(itemId.toString()))
                .andExpect(jsonPath("$[0].dueDate").value("2026-09-30"))
                .andExpect(jsonPath("$[0].createdByName").value("Nihan Öztürk"));
    }

    @Test
    void marksADocumentReviewed() throws Exception {
        when(startupDocumentService.review(DOCUMENT_ID, MENTOR_ID)).thenReturn(deck());

        mockMvc.perform(post("/api/documents/{id}/review", DOCUMENT_ID)
                        .param("actorId", MENTOR_ID.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DOCUMENT_ID.toString()));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/organizations/{id}/documents", ORG_ID).param("actorId", ACTOR_ID.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsTheStartupsDocuments() throws Exception {
        when(startupDocumentService.listForOrganization(ORG_ID, ACTOR_ID)).thenReturn(List.of(deck()));

        mockMvc.perform(get("/api/organizations/{id}/documents", ORG_ID)
                        .param("actorId", ACTOR_ID.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].originalFilename").value("Pitch Deck v2.pdf"))
                .andExpect(jsonPath("$[0].shares[0].mentorName").value("Nihan Öztürk"));
    }

    @Test
    void rejectsAListWithoutAnActor() throws Exception {
        mockMvc.perform(get("/api/organizations/{id}/documents", ORG_ID).with(user("test-user")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(startupDocumentService);
    }

    @Test
    void returnsADownloadLink() throws Exception {
        when(startupDocumentService.downloadUrl(DOCUMENT_ID, ACTOR_ID))
                .thenReturn(new DocumentDownloadResponse("https://bucket.s3/deck.pdf?sig", "deck.pdf", "application/pdf"));

        mockMvc.perform(get("/api/documents/{id}/download-url", DOCUMENT_ID)
                        .param("actorId", ACTOR_ID.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://bucket.s3/deck.pdf?sig"));
    }

    @Test
    void sharesADocument() throws Exception {
        when(startupDocumentService.share(eq(DOCUMENT_ID), any(ShareDocumentRequest.class))).thenReturn(deck());

        mockMvc.perform(post("/api/documents/{id}/shares", DOCUMENT_ID)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "actorId": "%s", "mentorId": "%s" }
                                """.formatted(ACTOR_ID, MENTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shares[0].mentorId").value(MENTOR_ID.toString()));

        verify(startupDocumentService).share(DOCUMENT_ID, new ShareDocumentRequest(ACTOR_ID, MENTOR_ID));
    }

    @Test
    void unsharesADocument() throws Exception {
        when(startupDocumentService.unshare(DOCUMENT_ID, MENTOR_ID, ACTOR_ID)).thenReturn(deck());

        mockMvc.perform(delete("/api/documents/{id}/shares/{mentorId}", DOCUMENT_ID, MENTOR_ID)
                        .param("actorId", ACTOR_ID.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk());

        verify(startupDocumentService).unshare(DOCUMENT_ID, MENTOR_ID, ACTOR_ID);
    }

    @Test
    void deletesADocument() throws Exception {
        mockMvc.perform(delete("/api/documents/{id}", DOCUMENT_ID)
                        .param("actorId", ACTOR_ID.toString())
                        .with(user("test-user")))
                .andExpect(status().isNoContent());

        verify(startupDocumentService).delete(DOCUMENT_ID, ACTOR_ID);
    }
}
