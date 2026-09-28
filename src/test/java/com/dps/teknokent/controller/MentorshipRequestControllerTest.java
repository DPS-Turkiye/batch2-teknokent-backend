package com.dps.teknokent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.DecideMentorshipRequest;
import com.dps.teknokent.dto.requests.ReplyToMentorshipRequest;
import com.dps.teknokent.dto.requests.UpdatePlannedSessionsRequest;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.service.MeetingService;
import com.dps.teknokent.service.MentorshipRequestService;

@WebMvcTest(MentorshipRequestController.class)
@Import(SecurityConfig.class)
class MentorshipRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MentorshipRequestService mentorshipRequestService;

    @MockitoBean
    private MeetingService meetingService;

    @Test
    void requiresAuthenticationForList() throws Exception {
        mockMvc.perform(get("/api/mentorship-requests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsMentorshipRequestsFilteredByRequester() throws Exception {
        UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000301");

        when(mentorshipRequestService.findAllForActor(requesterId, requesterId, null, null)).thenReturn(List.of(new MentorshipRequestResponse(
                requestId,
                requesterId,
                "Ayşe Demir",
                organizationId,
                "Teknokent",
                mentorId,
                "Dr. Kemal Arslan",
                "Arda Demir",
                "Product strategy",
                "We need help validating our product roadmap.",
                MentorshipRequestUrgency.THIS_WEEK,
                MentorshipRequestFormat.SINGLE_MEETING,
                MeetingMode.ONLINE,
                MentorshipRequestStatus.PENDING,
                "We are building a SaaS analytics product.",
                null,
                null,
                Instant.parse("2026-08-20T08:00:00Z"),
                Instant.parse("2026-08-20T08:05:00Z"),
                List.of(),
                null,
                null,
                null,
                null
        )));

        mockMvc.perform(get("/api/mentorship-requests")
                        .param("requesterId", requesterId.toString())
                        .param("actorId", requesterId.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId.toString()))
                .andExpect(jsonPath("$[0].requestedForName").value("Arda Demir"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void detailPassesSessionActorToOwnershipCheck() throws Exception {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000399");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000398");
        when(mentorshipRequestService.findByIdForActor(requestId, actorId))
                .thenThrow(new com.dps.teknokent.exception.ResourceNotFoundException("Mentorship request not found"));

        mockMvc.perform(get("/api/mentorship-requests/{id}", requestId)
                        .param("actorId", actorId.toString())
                        .with(user("test-user")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createsMentorshipRequest() throws Exception {
        UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000302");

        when(mentorshipRequestService.create(any(CreateMentorshipRequestSubmission.class))).thenReturn(new MentorshipRequestResponse(
                requestId,
                requesterId,
                "Ayşe Demir",
                organizationId,
                "Teknokent",
                mentorId,
                "Dr. Kemal Arslan",
                "Arda Demir",
                "Product strategy",
                "We need mentorship around roadmap validation.",
                MentorshipRequestUrgency.NEXT_TWO_WEEKS,
                MentorshipRequestFormat.REGULAR,
                MeetingMode.ONLINE,
                MentorshipRequestStatus.PENDING,
                "We are a B2B SaaS startup.",
                null,
                null,
                Instant.parse("2026-08-21T10:00:00Z"),
                Instant.parse("2026-08-21T10:05:00Z"),
                List.of(),
                null,
                null,
                null,
                null
        ));

        mockMvc.perform(post("/api/mentorship-requests")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requesterId": "00000000-0000-0000-0000-000000000001",
                                  "organizationId": "00000000-0000-0000-0000-000000000101",
                                  "mentorId": "00000000-0000-0000-0000-000000000201",
                                  "requestedForName": "Arda Demir",
                                  "supportArea": "Product strategy",
                                  "summary": "We need mentorship around roadmap validation.",
                                  "urgency": "NEXT_TWO_WEEKS",
                                  "mentorshipFormat": "REGULAR",
                                  "meetingMode": "ONLINE",
                                  "requesterMessage": "We are a B2B SaaS startup."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.supportArea").value("Product strategy"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void decidesOnMentorshipRequest() throws Exception {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000303");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        when(mentorshipRequestService.decide(eq(requestId), any(DecideMentorshipRequest.class))).thenReturn(new MentorshipRequestResponse(
                requestId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Ayşe Demir",
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                "Teknokent",
                mentorId,
                "Dr. Kemal Arslan",
                "Arda Demir",
                "Product strategy",
                "We need guidance on roadmap validation.",
                MentorshipRequestUrgency.NEXT_TWO_WEEKS,
                MentorshipRequestFormat.REGULAR,
                MeetingMode.ONLINE,
                MentorshipRequestStatus.ACCEPTED,
                "We are a B2B SaaS startup.",
                "Let's schedule a first meeting.",
                null,
                Instant.parse("2026-08-21T12:00:00Z"),
                Instant.parse("2026-08-21T12:10:00Z"),
                List.of(),
                null,
                null,
                null,
                null
        ));

        mockMvc.perform(patch("/api/mentorship-requests/{id}/decision", requestId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mentorId": "00000000-0000-0000-0000-000000000201",
                                  "status": "ACCEPTED",
                                  "message": "Let's schedule a first meeting."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.mentorMessage").value("Let's schedule a first meeting."));
    }

    @Test
    void repliesToMentorshipRequest() throws Exception {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000304");
        UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(mentorshipRequestService.reply(eq(requestId), any(ReplyToMentorshipRequest.class))).thenReturn(new MentorshipRequestResponse(
                requestId,
                requesterId,
                "Ayşe Demir",
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                "Teknokent",
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                "Dr. Kemal Arslan",
                "Arda Demir",
                "Product strategy",
                "We need guidance on roadmap validation.",
                MentorshipRequestUrgency.THIS_MONTH,
                MentorshipRequestFormat.SHORT_TERM,
                MeetingMode.NO_PREFERENCE,
                MentorshipRequestStatus.MORE_INFO_REQUESTED,
                "We are in early validation.",
                "Please share your current traction and revenue metrics.",
                null,
                Instant.parse("2026-08-21T14:00:00Z"),
                Instant.parse("2026-08-21T14:05:00Z"),
                List.of(),
                null,
                null,
                null,
                null
        ));

        mockMvc.perform(post("/api/mentorship-requests/{id}/messages", requestId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requesterId": "00000000-0000-0000-0000-000000000001",
                                  "message": "We have validation data from 18 pilot users."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("MORE_INFO_REQUESTED"));
    }

    @Test
    void plansTheSessionsOfAnAcceptedMentorship() throws Exception {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000305");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        when(mentorshipRequestService.updatePlannedSessions(requestId, new UpdatePlannedSessionsRequest(mentorId, 6)))
                .thenReturn(new MentorshipRequestResponse(
                        requestId,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "Ayşe Demir",
                        UUID.fromString("00000000-0000-0000-0000-000000000101"),
                        "Teknokent",
                        mentorId,
                        "Dr. Kemal Arslan",
                        "Arda Demir",
                        "Product strategy",
                        "We need guidance on roadmap validation.",
                        MentorshipRequestUrgency.THIS_MONTH,
                        MentorshipRequestFormat.REGULAR,
                        MeetingMode.ONLINE,
                        MentorshipRequestStatus.ACCEPTED,
                        "We are a B2B SaaS startup.",
                        null,
                        null,
                        Instant.parse("2026-08-21T14:00:00Z"),
                        Instant.parse("2026-08-21T14:05:00Z"),
                        List.of(),
                        null,
                        6,
                        null,
                        null
                ));

        mockMvc.perform(patch("/api/mentorship-requests/{id}/planned-sessions", requestId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mentorId": "00000000-0000-0000-0000-000000000201",
                                  "plannedSessionCount": 6
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedSessionCount").value(6));
    }

    @Test
    void rejectsMoreSessionsThanAYearOfWeeks() throws Exception {
        mockMvc.perform(patch("/api/mentorship-requests/{id}/planned-sessions",
                        UUID.fromString("00000000-0000-0000-0000-000000000305"))
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mentorId": "00000000-0000-0000-0000-000000000201",
                                  "plannedSessionCount": 53
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCreateRequestWhenSummaryIsBlank() throws Exception {
        mockMvc.perform(post("/api/mentorship-requests")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requesterId": "00000000-0000-0000-0000-000000000001",
                                  "organizationId": "00000000-0000-0000-0000-000000000101",
                                  "mentorId": "00000000-0000-0000-0000-000000000201",
                                  "requestedForName": "Arda Demir",
                                  "supportArea": "Product strategy",
                                  "summary": "",
                                  "urgency": "THIS_WEEK",
                                  "mentorshipFormat": "SINGLE_MEETING",
                                  "meetingMode": "ONLINE"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void marksTheConversationReadForTheCaller() throws Exception {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        mockMvc.perform(post("/api/mentorship-requests/{id}/read", requestId)
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "actorId": "00000000-0000-0000-0000-000000000201" }
                                """))
                .andExpect(status().isOk());

        verify(mentorshipRequestService).markRead(requestId, actorId);
    }

    @Test
    void rejectsMarkingReadWithoutAnActor() throws Exception {
        mockMvc.perform(post("/api/mentorship-requests/{id}/read", UUID.randomUUID())
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
