package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    @InjectMocks
    private AppUserService appUserService;

    @Test
    void findAll_returnsAllUsersSortedByFullNameWhenRoleIsNull() {
        AppUser zeynep = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "zeynep", "Zeynep Arslan", UserRole.MENTOR);
        AppUser ahmet = user(UUID.fromString("00000000-0000-0000-0000-000000000102"), "ahmet", "Ahmet Yılmaz", UserRole.MENTEE);

        when(appUserRepository.findAll()).thenReturn(List.of(zeynep, ahmet));

        var result = appUserService.findAll(null);

        assertThat(result).extracting(user -> user.fullName())
                .containsExactly("Ahmet Yılmaz", "Zeynep Arslan");
        assertThat(result).hasSize(2);
    }

    @Test
    void findAll_filtersByRoleAndSortsNamesCaseInsensitively() {
        AppUser mentorA = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentorA", "Zeynep Arslan", UserRole.MENTOR);
        AppUser mentorB = user(UUID.fromString("00000000-0000-0000-0000-000000000202"), "mentorB", "Ahmet Yılmaz", UserRole.MENTOR);
        AppUser mentee = user(UUID.fromString("00000000-0000-0000-0000-000000000203"), "mentee", "Elif Demir", UserRole.MENTEE);

        when(appUserRepository.findAll()).thenReturn(List.of(mentorA, mentee, mentorB));

        var result = appUserService.findAll(UserRole.MENTOR);

        assertThat(result).extracting(user -> user.fullName())
                .containsExactly("Ahmet Yılmaz", "Zeynep Arslan");
        assertThat(result).allMatch(user -> user.role() == UserRole.MENTOR);
    }

    @Test
    void findById_returnsMappedUserWhenFound() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000301");
        Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000401"), "Teknokent");
        AppUser user = user(id, "mentor", "Dr. Kemal Arslan", UserRole.MENTOR, organization, "Ürün ve Araştırma Direktörü");

        when(appUserRepository.findById(id)).thenReturn(Optional.of(user));

        var result = appUserService.findById(id);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.email()).isEqualTo("mentor");
        assertThat(result.fullName()).isEqualTo("Dr. Kemal Arslan");
        assertThat(result.role()).isEqualTo(UserRole.MENTOR);
        assertThat(result.organizationId()).isEqualTo(organization.getId());
        assertThat(result.organizationName()).isEqualTo("Teknokent");
    }

    @Test
    void findById_throwsWhenUserDoesNotExist() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000999");

        when(appUserRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appUserService.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found: " + id);
    }

    private static AppUser user(UUID id, String email, String fullName, UserRole role) {
        return user(id, email, fullName, role, null, null);
    }

    private static AppUser user(UUID id, String email, String fullName, UserRole role, Organization organization, String jobTitle) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        user.setOrganization(organization);
        user.setJobTitle(jobTitle);
        return user;
    }

    private static Organization organization(UUID id, String name) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName(name);
        return organization;
    }
}
