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
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.service.MeetingService;

@WebMvcTest(MeetingController.class)
@Import(SecurityConfig.class)
class MeetingControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private MeetingService meetingService;

        @Test
        void requiresAuthenticationForMeetings() throws Exception {
                mockMvc.perform(get("/api/meetings"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        void returnsMeetingsWithoutOrganizationFilter() throws Exception {
                UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000002");

                when(meetingService.findAll(null, null)).thenReturn(List.of(new MeetingResponse(
                                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                                null,
                                organizationId,
                                "Teknokent",
                                mentorId,
                                "Dr. Kemal Arslan",
                                Instant.parse("2026-09-05T10:00:00Z"),
                                60,
                                "Product strategy review",
                                "Bring questions",
                                "Action items will be shared",
                                "https://meet.google.com/xyz-abc-def",
                                MeetingStatus.SCHEDULED)));

                mockMvc.perform(get("/api/meetings").with(user("test-user")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].organizationId").value(organizationId.toString()))
                                .andExpect(jsonPath("$[0].mentorName").value("Dr. Kemal Arslan"))
                                .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
        }

        @Test
        void returnsMeetingsForSpecificOrganization() throws Exception {
                UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000003");

                when(meetingService.findAll(organizationId, null)).thenReturn(List.of(new MeetingResponse(
                                UUID.fromString("00000000-0000-0000-0000-000000000022"),
                                null,
                                organizationId,
                                "Teknokent",
                                mentorId,
                                "Ece Toprak",
                                Instant.parse("2026-09-10T14:30:00Z"),
                                90,
                                "MVP review",
                                "Focus on adoption metrics",
                                "Follow-up after the meeting",
                                "https://meet.google.com/abc-defg-hij",
                                MeetingStatus.SCHEDULED)));

                mockMvc.perform(get("/api/meetings")
                                .param("organizationId", organizationId.toString())
                                .with(user("test-user")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].id").value("00000000-0000-0000-0000-000000000022"))
                                .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
        }

        @Test
        void returnsMeetingsForSpecificMentorAcrossOrganizations() throws Exception {
                UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000004");

                when(meetingService.findAll(null, mentorId)).thenReturn(List.of(new MeetingResponse(
                                UUID.fromString("00000000-0000-0000-0000-000000000044"),
                                null,
                                organizationId,
                                "GreenLoop",
                                mentorId,
                                "Leyla Aksoy",
                                Instant.parse("2026-09-12T09:00:00Z"),
                                60,
                                "Mentorship check-in",
                                null,
                                null,
                                "https://meet.google.com/mno-pqr-stu",
                                MeetingStatus.SCHEDULED)));

                mockMvc.perform(get("/api/meetings")
                                .param("mentorId", mentorId.toString())
                                .with(user("test-user")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].id").value("00000000-0000-0000-0000-000000000044"))
                                .andExpect(jsonPath("$[0].mentorName").value("Leyla Aksoy"));
        }

        @Test
        void createsMeeting() throws Exception {
                UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000033");

                when(meetingService.create(any(CreateMeetingRequest.class))).thenReturn(new MeetingResponse(
                                meetingId,
                                null,
                                organizationId,
                                "Teknokent",
                                mentorId,
                                "Dr. Kemal Arslan",
                                Instant.parse("2026-09-15T16:00:00Z"),
                                45,
                                "Growth roadmap session",
                                "Discuss next milestone",
                                "Share the final plan after meeting",
                                "https://meet.google.com/alpha-beta-gamma",
                                MeetingStatus.SCHEDULED));

                mockMvc.perform(post("/api/meetings")
                                .with(user("test-user"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "organizationId": "00000000-0000-0000-0000-000000000001",
                                                  "mentorId": "00000000-0000-0000-0000-000000000002",
                                                  "scheduledAt": "2026-09-15T16:00:00Z",
                                                  "durationMinutes": 45,
                                                  "agenda": "Growth roadmap session",
                                                  "notes": "Discuss next milestone",
                                                  "outcomes": "Share the final plan after meeting",
                                                  "meetLink": "https://meet.google.com/alpha-beta-gamma",
                                                  "status": "SCHEDULED"
                                                }
                                                """))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").value(meetingId.toString()))
                                .andExpect(jsonPath("$.agenda").value("Growth roadmap session"))
                                .andExpect(jsonPath("$.status").value("SCHEDULED"));
        }

        @Test
        void rejectsCreateMeetingWhenDurationIsZero() throws Exception {
                mockMvc.perform(post("/api/meetings")
                                .with(user("test-user"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "organizationId": "00000000-0000-0000-0000-000000000001",
                                                  "scheduledAt": "2026-09-15T16:00:00Z",
                                                  "durationMinutes": 0,
                                                  "agenda": "Invalid duration test",
                                                  "status": "SCHEDULED"
                                                }
                                                """))
                                .andExpect(status().isBadRequest());
        }
}
