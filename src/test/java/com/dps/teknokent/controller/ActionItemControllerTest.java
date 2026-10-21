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
import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.dto.requests.CreateActionItemRequest;
import com.dps.teknokent.dto.requests.UpdateActionItemRequest;
import com.dps.teknokent.dto.responses.ActionItemResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.service.ActionItemService;

@WebMvcTest(ActionItemController.class)
@Import(SecurityConfig.class)
class ActionItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActionItemService actionItemService;

    @Test
    void requiresAuthenticationForList() throws Exception {
        mockMvc.perform(get("/api/actions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsActionItems() throws Exception {
        UUID actionItemId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        when(actionItemService.findByScope(organizationId, null)).thenReturn(List.of(new ActionItemResponse(
                actionItemId,
                organizationId,
                "Teknokent",
                meetingId,
                ownerId,
                "Ahmet Yılmaz",
                "Prepare onboarding checklist",
                "Review onboarding docs and assign owners",
                ActionItemStatus.OPEN,
                LocalDate.of(2026, 9, 15),
                null,
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                null
        )));

        mockMvc.perform(get("/api/actions")
                        .param("organizationId", organizationId.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(actionItemId.toString()))
                .andExpect(jsonPath("$[0].title").value("Prepare onboarding checklist"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void returnsActionItemsScopedToAMentorshipRequest() throws Exception {
        UUID mentorshipRequestId = UUID.fromString("00000000-0000-0000-0000-000000000401");

        when(actionItemService.findByScope(null, mentorshipRequestId)).thenReturn(List.of());

        mockMvc.perform(get("/api/actions")
                        .param("mentorshipRequestId", mentorshipRequestId.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsListWithoutAScope() throws Exception {
        when(actionItemService.findByScope(null, null))
                .thenThrow(new InvalidRequestException("Provide exactly one of organizationId or mentorshipRequestId"));

        mockMvc.perform(get("/api/actions").with(user("test-user")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsActionItem() throws Exception {
        UUID actionItemId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        when(actionItemService.create(any(CreateActionItemRequest.class))).thenReturn(new ActionItemResponse(
                actionItemId,
                organizationId,
                "Teknokent",
                meetingId,
                ownerId,
                "Ahmet Yılmaz",
                "Prepare onboarding checklist",
                "Review onboarding docs and assign owners",
                ActionItemStatus.IN_PROGRESS,
                LocalDate.of(2026, 9, 15),
                null,
                Instant.parse("2026-08-20T10:30:00Z"),
                Instant.parse("2026-08-20T10:30:00Z"),
                null
        ));

        mockMvc.perform(post("/api/actions")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId": "00000000-0000-0000-0000-000000000001",
                                  "meetingId": "00000000-0000-0000-0000-000000000003",
                                  "ownerId": "00000000-0000-0000-0000-000000000002",
                                  "title": "Prepare onboarding checklist",
                                  "description": "Review onboarding docs and assign owners",
                                  "dueDate": "2026-09-15"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(actionItemId.toString()))
                .andExpect(jsonPath("$.title").value("Prepare onboarding checklist"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void rejectsCreateActionItemWhenTitleIsBlank() throws Exception {
        mockMvc.perform(post("/api/actions")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId": "00000000-0000-0000-0000-000000000001",
                                  "ownerId": "00000000-0000-0000-0000-000000000002",
                                  "title": "",
                                  "description": "Missing title"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesActionItem() throws Exception {
        UUID actionItemId = UUID.fromString("00000000-0000-0000-0000-000000000044");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(actionItemService.update(eq(actionItemId), eq(organizationId), eq(null), any(UpdateActionItemRequest.class))).thenReturn(new ActionItemResponse(
                actionItemId,
                organizationId,
                "Teknokent",
                null,
                ownerId,
                "Ahmet Yılmaz",
                "Finalize onboarding",
                "All handoff docs completed",
                ActionItemStatus.COMPLETED,
                LocalDate.of(2026, 9, 20),
                "Completed after review",
                Instant.parse("2026-08-20T11:00:00Z"),
                Instant.parse("2026-08-20T11:05:00Z"),
                null
        ));

        mockMvc.perform(patch("/api/actions/{id}", actionItemId)
                        .param("organizationId", organizationId.toString())
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "00000000-0000-0000-0000-000000000002",
                                  "title": "Finalize onboarding",
                                  "status": "COMPLETED",
                                  "dueDate": "2026-09-20",
                                  "completionNotes": "Completed after review"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(actionItemId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completionNotes").value("Completed after review"));
    }

    @Test
    void listsSeveralRequestsItemsInOneCall() throws Exception {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000401");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000402");
        when(actionItemService.findByRequests(List.of(first, second))).thenReturn(List.of(new ActionItemResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Teknokent",
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "Ahmet Yılmaz",
                "Prepare onboarding checklist",
                null,
                ActionItemStatus.OPEN,
                null,
                null,
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                second
        )));

        mockMvc.perform(get("/api/actions")
                        .param("mentorshipRequestIds", first.toString(), second.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mentorshipRequestId").value(second.toString()));
    }
}
