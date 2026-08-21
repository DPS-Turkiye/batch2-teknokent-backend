package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ProgressStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProgressUpdateRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgressUpdate;
import com.dps.teknokent.repository.ProgressUpdateRepository;

@ExtendWith(MockitoExtension.class)
class ProgressUpdateServiceTest {

    @Mock
    private ProgressUpdateRepository progressUpdateRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @InjectMocks
    private ProgressUpdateService progressUpdateService;

    @Test
    void findByOrganization_validatesOrganizationAndMapsUpdates() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        Organization organization = organization(organizationId, "Teknokent");
        AppUser author = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);

        ProgressUpdate update = progressUpdate(
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                organization,
                author,
                "Product",
                ProgressStatus.ON_TRACK,
                "MVP validation is moving well.",
                "Six pilot users signed up.",
                LocalDate.of(2026, 8, 20));

        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(progressUpdateRepository.findByOrganizationIdOrderByRecordedAtDesc(organizationId)).thenReturn(List.of(update));

        var result = progressUpdateService.findByOrganization(organizationId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().organizationId()).isEqualTo(organizationId);
        assertThat(result.getFirst().authorName()).isEqualTo("Dr. Kemal Arslan");
        assertThat(result.getFirst().status()).isEqualTo(ProgressStatus.ON_TRACK);
    }

    @Test
    void create_requiresOrganizationAndAuthorThenSavesUpdate() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID authorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID updateId = UUID.fromString("00000000-0000-0000-0000-000000000301");

        Organization organization = organization(organizationId, "Teknokent");
        AppUser author = user(authorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
        CreateProgressUpdateRequest request = new CreateProgressUpdateRequest(
                authorId,
                "Product",
                ProgressStatus.ON_TRACK,
                "MVP validation milestones are progressing.",
                "Two new pilot customers onboarded.",
                LocalDate.of(2026, 8, 21));

        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(domainLookupService.requireUser(authorId)).thenReturn(author);
        when(progressUpdateRepository.saveAndFlush(any(ProgressUpdate.class))).thenAnswer(invocation -> {
            ProgressUpdate saved = invocation.getArgument(0);
            saved.setId(updateId);
            saved.setCreatedAt(Instant.parse("2026-08-21T09:00:00Z"));
            return saved;
        });

        var result = progressUpdateService.create(organizationId, request);

        assertThat(result.id()).isEqualTo(updateId);
        assertThat(result.organizationId()).isEqualTo(organizationId);
        assertThat(result.authorId()).isEqualTo(authorId);
        assertThat(result.category()).isEqualTo("Product");
        assertThat(result.summary()).isEqualTo("MVP validation milestones are progressing.");
    }

    private static ProgressUpdate progressUpdate(UUID id, Organization organization, AppUser author, String category,
            ProgressStatus status, String summary, String evidence, LocalDate recordedAt) {
        ProgressUpdate update = new ProgressUpdate();
        update.setId(id);
        update.setOrganization(organization);
        update.setAuthor(author);
        update.setCategory(category);
        update.setStatus(status);
        update.setSummary(summary);
        update.setEvidence(evidence);
        update.setRecordedAt(recordedAt);
        update.setCreatedAt(Instant.parse("2026-08-21T08:00:00Z"));
        return update;
    }

    private static Organization organization(UUID id, String name) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName(name);
        return organization;
    }

    private static AppUser user(UUID id, String email, String fullName, UserRole role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        return user;
    }
}
