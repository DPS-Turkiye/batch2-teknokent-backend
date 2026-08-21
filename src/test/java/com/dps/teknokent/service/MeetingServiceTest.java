package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.MeetingRepository;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @InjectMocks
    private MeetingService meetingService;

    @Test
    void findAll_withoutOrganizationId_returnsAllMeetingsOrderedBySchedule() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        Meeting meeting = meeting(UUID.fromString("00000000-0000-0000-0000-000000000301"), organizationId, mentorId,
                "Roadmap review", MeetingStatus.SCHEDULED, 60);

        when(meetingRepository.findAllByOrderByScheduledAtDesc()).thenReturn(List.of(meeting));

        var result = meetingService.findAll(null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(meeting.getId());
        assertThat(result.getFirst().status()).isEqualTo(MeetingStatus.SCHEDULED);
        assertThat(result.getFirst().agenda()).isEqualTo("Roadmap review");
    }

    @Test
    void findAll_withOrganizationId_returnsOnlyThatOrganizationsMeetings() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        Meeting meeting = meeting(UUID.fromString("00000000-0000-0000-0000-000000000302"), organizationId, mentorId,
                "MVP check-in", MeetingStatus.SCHEDULED, 90);

        when(meetingRepository.findByOrganizationIdOrderByScheduledAtDesc(organizationId)).thenReturn(List.of(meeting));

        var result = meetingService.findAll(organizationId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().organizationId()).isEqualTo(organizationId);
        assertThat(result.getFirst().mentorId()).isEqualTo(mentorId);
    }

    @Test
    void create_setsMentorWhenProvidedAndMentorRoleIsValid() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");

        Organization organization = organization(organizationId, "Teknokent");
        AppUser mentor = user(mentorId, "Dr. Kemal Arslan", UserRole.MENTOR);
        CreateMeetingRequest request = new CreateMeetingRequest(
                organizationId,
                mentorId,
                Instant.parse("2026-09-15T16:00:00Z"),
                45,
                "Growth roadmap session",
                "Discuss next milestone",
                "Share final plan after review",
                "https://meet.google.com/demo-link",
                MeetingStatus.SCHEDULED);

        when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting saved = invocation.getArgument(0);
            saved.setId(meetingId);
            return saved;
        });

        var result = meetingService.create(request);

        assertThat(result.id()).isEqualTo(meetingId);
        assertThat(result.organizationId()).isEqualTo(organizationId);
        assertThat(result.mentorId()).isEqualTo(mentorId);
        assertThat(result.status()).isEqualTo(MeetingStatus.SCHEDULED);
    }

    @Test
    void create_throwsWhenMentorIsNotMentorRole() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000202");

        Organization organization = organization(organizationId, "Teknokent");
        AppUser nonMentor = user(userId, "Elif Demir", UserRole.MENTEE);
        CreateMeetingRequest request = new CreateMeetingRequest(
                organizationId,
                userId,
                Instant.parse("2026-09-15T16:00:00Z"),
                45,
                "Growth roadmap session",
                null,
                null,
                null,
                MeetingStatus.SCHEDULED);

        when(domainLookupService.requireUser(userId)).thenReturn(nonMentor);

        assertThatThrownBy(() -> meetingService.create(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("MENTOR role");
    }

    private static Meeting meeting(UUID id, UUID organizationId, UUID mentorId, String agenda, MeetingStatus status, int durationMinutes) {
        Meeting meeting = new Meeting();
        meeting.setId(id);
        Organization organization = organization(organizationId, "Teknokent");
        meeting.setOrganization(organization);
        meeting.setMentor(user(mentorId, "Dr. Kemal Arslan", UserRole.MENTOR));
        meeting.setScheduledAt(Instant.parse("2026-09-15T16:00:00Z"));
        meeting.setDurationMinutes(durationMinutes);
        meeting.setAgenda(agenda);
        meeting.setStatus(status);
        return meeting;
    }

    private static Organization organization(UUID id, String name) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName(name);
        return organization;
    }

    private static AppUser user(UUID id, String fullName, UserRole role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setFullName(fullName);
        user.setRole(role);
        return user;
    }
}
