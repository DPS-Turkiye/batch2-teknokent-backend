package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.MentorshipRepository;

@ExtendWith(MockitoExtension.class)
class MentorshipServiceTest {

    @Mock
    private MentorshipRepository mentorshipRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @InjectMocks
    private MentorshipService mentorshipService;

    @Test
    void findAll_returnsAllMentorshipsOrderedByCreatedAtDesc() {
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");

        Mentorship mentorship = mentorship(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                mentorId,
                organizationId,
                "Product strategy coaching",
                MentorshipStatus.ACTIVE,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 30));

        when(mentorshipRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(mentorship));

        var result = mentorshipService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().mentorId()).isEqualTo(mentorId);
        assertThat(result.getFirst().organizationId()).isEqualTo(organizationId);
        assertThat(result.getFirst().status()).isEqualTo(MentorshipStatus.ACTIVE);
    }

    @Test
    void create_rejectsEndDateBeforeStartDate() {
        CreateMentorshipRequest request = new CreateMentorshipRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                MentorshipStatus.PLANNED,
                LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 9, 1),
                "Roadmap guidance");

        assertThatThrownBy(() -> mentorshipService.create(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("endDate must be on or after startDate");
    }

    @Test
    void create_requiresMentorRoleAndPersistsMentorship() {
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");

        AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
        Organization organization = organization(organizationId, "Teknokent");
        CreateMentorshipRequest request = new CreateMentorshipRequest(
                mentorId,
                organizationId,
                MentorshipStatus.ACTIVE,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 12, 10),
                "Build a scalable product strategy");

        when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(mentorshipRepository.save(any(Mentorship.class))).thenAnswer(invocation -> {
            Mentorship saved = invocation.getArgument(0);
            saved.setId(mentorshipId);
            return saved;
        });

        var result = mentorshipService.create(request);

        assertThat(result.id()).isEqualTo(mentorshipId);
        assertThat(result.mentorId()).isEqualTo(mentorId);
        assertThat(result.organizationId()).isEqualTo(organizationId);
        assertThat(result.status()).isEqualTo(MentorshipStatus.ACTIVE);
        assertThat(result.goals()).isEqualTo("Build a scalable product strategy");
    }

    @Test
    void create_rejectsNonMentorUsers() {
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");

        AppUser nonMentor = user(mentorId, "mentee@tekno.com", "Ayşe Demir", UserRole.MENTEE);
        CreateMentorshipRequest request = new CreateMentorshipRequest(
                mentorId,
                organizationId,
                MentorshipStatus.PLANNED,
                LocalDate.of(2026, 8, 10),
                null,
                "Get advice");

        when(domainLookupService.requireUser(mentorId)).thenReturn(nonMentor);

        assertThatThrownBy(() -> mentorshipService.create(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("MENTOR role");
    }

    private static Mentorship mentorship(UUID id, UUID mentorId, UUID organizationId, String goals,
            MentorshipStatus status, LocalDate startDate, LocalDate endDate) {
        Mentorship mentorship = new Mentorship();
        mentorship.setId(id);
        mentorship.setMentor(user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR));
        mentorship.setOrganization(organization(organizationId, "Teknokent"));
        mentorship.setStatus(status);
        mentorship.setStartDate(startDate);
        mentorship.setEndDate(endDate);
        mentorship.setGoals(goals);
        return mentorship;
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
}
