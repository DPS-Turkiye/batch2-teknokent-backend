package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.data.domain.Sort;

import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.OrganizationRepository;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @InjectMocks
    private OrganizationService organizationService;

    @Test
    void findAll_returnsOrganizationsSortedByNameAscending() {
        Organization a = organization(UUID.fromString("00000000-0000-0000-0000-000000000101"), "Acme", "Startup");
        Organization b = organization(UUID.fromString("00000000-0000-0000-0000-000000000102"), "Bora Labs", "Scale-up");

        when(organizationRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))).thenReturn(List.of(a, b));

        var result = organizationService.findAll();

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().name()).isEqualTo("Acme");
        assertThat(result.get(1).name()).isEqualTo("Bora Labs");
    }

    @Test
    void findById_returnsOrganizationFromDomainLookup() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000201");
        Organization organization = organization(id, "Teknokent", "Eğitim ve girişim destek merkezi");

        when(domainLookupService.requireOrganization(id)).thenReturn(organization);

        var result = organizationService.findById(id);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.name()).isEqualTo("Teknokent");
        assertThat(result.description()).isEqualTo("Eğitim ve girişim destek merkezi");
    }

    @Test
    void create_savesAndMapsOrganization() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000301");
        CreateOrganizationRequest request = new CreateOrganizationRequest("İnovasyon Merkezi", "Girişimci ekosistemine destek");

        when(organizationRepository.saveAndFlush(any(Organization.class))).thenAnswer(invocation -> {
            Organization saved = invocation.getArgument(0);
            saved.setId(id);
            saved.setCreatedAt(Instant.parse("2026-08-21T08:00:00Z"));
            saved.setUpdatedAt(Instant.parse("2026-08-21T08:05:00Z"));
            return saved;
        });

        var result = organizationService.create(request);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.name()).isEqualTo("İnovasyon Merkezi");
        assertThat(result.description()).isEqualTo("Girişimci ekosistemine destek");
    }

    private static Organization organization(UUID id, String name, String description) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName(name);
        organization.setDescription(description);
        organization.setCreatedAt(Instant.parse("2026-08-21T09:00:00Z"));
        organization.setUpdatedAt(Instant.parse("2026-08-21T09:05:00Z"));
        return organization;
    }
}
