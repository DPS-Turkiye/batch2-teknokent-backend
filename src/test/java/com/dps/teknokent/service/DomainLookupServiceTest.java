package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.OrganizationRepository;

@ExtendWith(MockitoExtension.class)
class DomainLookupServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private MeetingRepository meetingRepository;

    @InjectMocks
    private DomainLookupService domainLookupService;

    @Test
    void requireUser_returnsUserWhenPresent() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        AppUser expected = user(userId, "mentor@teknokent.demo", "Dr. Kemal Arslan", UserRole.MENTOR);

        when(appUserRepository.findById(userId)).thenReturn(Optional.of(expected));

        AppUser result = domainLookupService.requireUser(userId);

        assertThat(result).isSameAs(expected);
        assertThat(result.getFullName()).isEqualTo("Dr. Kemal Arslan");
    }

    @Test
    void requireUser_throwsWhenUserMissing() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000999");

        when(appUserRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> domainLookupService.requireUser(userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found: " + userId);
    }

    @Test
    void requireOrganization_returnsOrganizationWhenPresent() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        Organization expected = organization(organizationId, "Teknokent");

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(expected));

        Organization result = domainLookupService.requireOrganization(organizationId);

        assertThat(result).isSameAs(expected);
        assertThat(result.getName()).isEqualTo("Teknokent");
    }

    @Test
    void requireOrganization_throwsWhenOrganizationMissing() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000999");

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> domainLookupService.requireOrganization(organizationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organization not found: " + organizationId);
    }

    @Test
    void requireMeeting_returnsMeetingWhenPresent() {
        UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        Meeting expected = meeting(meetingId, "Roadmap Check-in", MeetingStatus.SCHEDULED);

        when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(expected));

        Meeting result = domainLookupService.requireMeeting(meetingId);

        assertThat(result).isSameAs(expected);
        assertThat(result.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
    }

    @Test
    void requireMeeting_throwsWhenMeetingMissing() {
        UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000999");

        when(meetingRepository.findById(meetingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> domainLookupService.requireMeeting(meetingId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Meeting not found: " + meetingId);
    }

    private static AppUser user(UUID id, String email, String fullName, UserRole role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        return user;
    }

    private static Organization organization(UUID id, String name) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName(name);
        return organization;
    }

    private static Meeting meeting(UUID id, String agenda, MeetingStatus status) {
        Meeting meeting = new Meeting();
        meeting.setId(id);
        meeting.setAgenda(agenda);
        meeting.setStatus(status);
        meeting.setScheduledAt(Instant.parse("2026-09-15T10:00:00Z"));
        return meeting;
    }
}
