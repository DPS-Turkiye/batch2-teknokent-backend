package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.dto.requests.CreateBottleneckRequest;
import com.dps.teknokent.dto.requests.UpdateBottleneckRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.BottleneckRepository;

@ExtendWith(MockitoExtension.class)
class BottleneckServiceTest {

    @Mock
    private BottleneckRepository bottleneckRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @InjectMocks
    private BottleneckService bottleneckService;

    @Test
    void findByOrganization_returnsBottlenecksForOrganizationOrderedByCreationDate() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        Organization organization = organization(organizationId, "Teknokent");
        AppUser owner = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "Ayşe Yılmaz");

        Bottleneck bottleneck = new Bottleneck();
        bottleneck.setId(UUID.fromString("00000000-0000-0000-0000-000000000301"));
        bottleneck.setOrganization(organization);
        bottleneck.setOwner(owner);
        bottleneck.setTitle("Kullanıcı akışı takılıp kaldı");
        bottleneck.setDescription("Kayıt sonrası onay akışı bozuldu");
        bottleneck.setSeverity(BottleneckSeverity.HIGH);
        bottleneck.setStatus(BottleneckStatus.OPEN);
        bottleneck.setTargetResolutionDate(LocalDate.of(2026, 9, 30));
        bottleneck.setCreatedAt(Instant.parse("2026-08-20T09:00:00Z"));
        bottleneck.setUpdatedAt(Instant.parse("2026-08-20T09:30:00Z"));

        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(bottleneckRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)).thenReturn(List.of(bottleneck));

        var result = bottleneckService.findByOrganization(organizationId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(bottleneck.getId());
        assertThat(result.getFirst().organizationId()).isEqualTo(organizationId);
        assertThat(result.getFirst().title()).isEqualTo("Kullanıcı akışı takılıp kaldı");
        assertThat(result.getFirst().severity()).isEqualTo(BottleneckSeverity.HIGH);
    }

    @Test
    void create_createsBottleneckWithOpenStatus() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        Organization organization = organization(organizationId, "Teknokent");
        AppUser owner = user(ownerId, "Ayşe Yılmaz");

        CreateBottleneckRequest request = new CreateBottleneckRequest(
                ownerId,
                "Kullanıcı akışı takılıp kaldı",
                "Kayıt sonrası onay akışı bozuldu",
                BottleneckSeverity.HIGH,
                LocalDate.of(2026, 9, 30));

        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(domainLookupService.requireUser(ownerId)).thenReturn(owner);
        when(bottleneckRepository.saveAndFlush(any(Bottleneck.class))).thenAnswer(invocation -> {
            Bottleneck saved = invocation.getArgument(0);
            saved.setId(UUID.fromString("00000000-0000-0000-0000-000000000302"));
            saved.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
            saved.setUpdatedAt(Instant.parse("2026-08-20T10:15:00Z"));
            return saved;
        });

        var result = bottleneckService.create(organizationId, request);

        assertThat(result.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000302"));
        assertThat(result.ownerId()).isEqualTo(ownerId);
        assertThat(result.ownerName()).isEqualTo("Ayşe Yılmaz");
        assertThat(result.title()).isEqualTo("Kullanıcı akışı takılıp kaldı");
        assertThat(result.status()).isEqualTo(BottleneckStatus.OPEN);
    }

    @Test
    void update_updatesBottleneckFields() {
        UUID bottleneckId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        Organization organization = organization(organizationId, "Teknokent");
        AppUser oldOwner = user(UUID.fromString("00000000-0000-0000-0000-000000000202"), "Mehmet Demir");
        AppUser newOwner = user(ownerId, "Ayşe Yılmaz");

        Bottleneck bottleneck = new Bottleneck();
        bottleneck.setId(bottleneckId);
        bottleneck.setOrganization(organization);
        bottleneck.setOwner(oldOwner);
        bottleneck.setTitle("Kullanıcı akışı takılıp kaldı");
        bottleneck.setDescription("Başlangıçta problem vardı");
        bottleneck.setSeverity(BottleneckSeverity.HIGH);
        bottleneck.setStatus(BottleneckStatus.OPEN);
        bottleneck.setTargetResolutionDate(LocalDate.of(2026, 9, 30));
        bottleneck.setResolutionNotes(null);
        bottleneck.setCreatedAt(Instant.parse("2026-08-20T09:00:00Z"));
        bottleneck.setUpdatedAt(Instant.parse("2026-08-20T09:30:00Z"));

        UpdateBottleneckRequest request = new UpdateBottleneckRequest(
                ownerId,
                "İş akışı düzeltildi",
                "Kullanıcı geri bildirimi sonrası düzeltildi",
                BottleneckSeverity.MEDIUM,
                BottleneckStatus.IN_PROGRESS,
                LocalDate.of(2026, 10, 5),
                "İlk müdahale tamamlandı");

        when(bottleneckRepository.findById(bottleneckId)).thenReturn(Optional.of(bottleneck));
        when(domainLookupService.requireUser(ownerId)).thenReturn(newOwner);
        when(bottleneckRepository.saveAndFlush(bottleneck)).thenReturn(bottleneck);

        var result = bottleneckService.update(bottleneckId, request);

        assertThat(result.title()).isEqualTo("İş akışı düzeltildi");
        assertThat(result.ownerId()).isEqualTo(ownerId);
        assertThat(result.severity()).isEqualTo(BottleneckSeverity.MEDIUM);
        assertThat(result.status()).isEqualTo(BottleneckStatus.IN_PROGRESS);
        assertThat(result.resolutionNotes()).isEqualTo("İlk müdahale tamamlandı");
    }

    @Test
    void update_throwsWhenBottleneckDoesNotExist() {
        UUID bottleneckId = UUID.fromString("00000000-0000-0000-0000-000000000999");

        when(bottleneckRepository.findById(bottleneckId)).thenReturn(Optional.empty());

        UpdateBottleneckRequest request = new UpdateBottleneckRequest(
                null,
                "Yeni başlık",
                null,
                null,
                BottleneckStatus.RESOLVED,
                null,
                "Sorun çözüldü");

        assertThatThrownBy(() -> bottleneckService.update(bottleneckId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bottleneck not found: " + bottleneckId);
    }

    private static Organization organization(UUID id, String name) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName(name);
        return organization;
    }

    private static AppUser user(UUID id, String fullName) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setFullName(fullName);
        return user;
    }
}
