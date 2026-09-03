package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.UpdateOrganizationRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.InviteCodeGenerator;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private static final UUID FOUNDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000811");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @Mock
    private InviteCodeGenerator inviteCodeGenerator;

    @InjectMocks
    private OrganizationService organizationService;

    @Test
    void getInviteCode_allowsFounderAndAdmin() {
        Organization organization = organizationWithFounder("OLDCODE12");
        AppUser founder = organization.getFounder();
        AppUser admin = user(ADMIN_ID, UserRole.ADMIN, UserAccountStatus.APPROVED);

        when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(founder);

        var founderView = organizationService.getInviteCode(ORGANIZATION_ID, FOUNDER_ID);
        assertThat(founderView.inviteCode()).isEqualTo("OLDCODE12");

        when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(admin);
        var adminView = organizationService.getInviteCode(ORGANIZATION_ID, ADMIN_ID);
        assertThat(adminView.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(adminView.inviteCode()).isEqualTo("OLDCODE12");
    }

    @Test
    void getInviteCode_rejectsOutsider() {
        Organization organization = organizationWithFounder("OLDCODE12");
        AppUser outsider = user(OTHER_ID, UserRole.MENTOR, UserAccountStatus.APPROVED);

        when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(OTHER_ID)).thenReturn(outsider);

        assertThatThrownBy(() -> organizationService.getInviteCode(ORGANIZATION_ID, OTHER_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("admin or the organization founder");
    }

    @Test
    void regenerateInviteCode_replacesCodeForApprovedFounder() {
        Organization organization = organizationWithFounder("OLDCODE12");
        AppUser founder = organization.getFounder();

        when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(founder);
        when(inviteCodeGenerator.nextUnique(any())).thenReturn("NEWCODE8");
        when(organizationRepository.save(organization)).thenReturn(organization);

        var result = organizationService.regenerateInviteCode(ORGANIZATION_ID, FOUNDER_ID);

        assertThat(result.inviteCode()).isEqualTo("NEWCODE8");
        assertThat(organization.getInviteCode()).isEqualTo("NEWCODE8");
    }

    @Test
    void regenerateInviteCode_rejectsPendingFounder() {
        Organization organization = organizationWithFounder("OLDCODE12");
        organization.getFounder().setStatus(UserAccountStatus.PENDING);

        when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());

        assertThatThrownBy(() -> organizationService.regenerateInviteCode(ORGANIZATION_ID, FOUNDER_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("approved founder");
    }

    @Test
    void regenerateInviteCode_rejectsAdmin() {
        Organization organization = organizationWithFounder("OLDCODE12");
        AppUser admin = user(ADMIN_ID, UserRole.ADMIN, UserAccountStatus.APPROVED);

        when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(admin);

        assertThatThrownBy(() -> organizationService.regenerateInviteCode(ORGANIZATION_ID, ADMIN_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("approved founder");
    }

    // The startup fields the wizard collects were shown back on the review page and
    // then dropped on submit until V35; this is the read-write half of that fix.
    @Test
    void update_storesTheStartupProfileForTheFounder() {
        Organization organization = organizationWithFounder("CODE1234");
        when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());
        when(organizationRepository.save(any(Organization.class))).thenAnswer(call -> call.getArgument(0));

        var response = organizationService.update(ORGANIZATION_ID, new UpdateOrganizationRequest(
                FOUNDER_ID, "Döngü", "Gönüllülük platformu", "dongu.co", "Gönüllüler bulunamıyor",
                "Beceri bazlı eşleştirme", "Elle tutulan listeler", "STK'lar", "SaaS", "MVP geliştiriliyor",
                "Build", List.of("Finans", "Pazarlama")));

        assertThat(response.website()).isEqualTo("dongu.co");
        assertThat(response.sector()).isEqualTo("SaaS");
        assertThat(response.supportAreas()).containsExactly("Finans", "Pazarlama");
        assertThat(organization.getProblem()).isEqualTo("Gönüllüler bulunamıyor");
        assertThat(organization.getTargetUser()).isEqualTo("STK'lar");
        assertThat(organization.getProgramLevel()).isEqualTo("Build");
    }

    // Editing a startup's own profile is the founder's, not another member's and
    // not an admin's, even though an admin may read the organization.
    @Test
    void update_rejectsAnyoneButTheFounder() {
        Organization organization = organizationWithFounder("CODE1234");
        when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(OTHER_ID))
                .thenReturn(user(OTHER_ID, UserRole.MENTEE, UserAccountStatus.APPROVED));

        assertThatThrownBy(() -> organizationService.update(ORGANIZATION_ID, new UpdateOrganizationRequest(
                OTHER_ID, "Döngü", null, null, null, null, null, null, null, null, null, List.of())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("founder");

        verify(organizationRepository, never()).save(any(Organization.class));
    }

    // A free-form chip list can arrive with blanks and repeats; neither belongs in
    // the database, and the order the founder arranged them in must survive.
    @Test
    void update_dropsBlankAndDuplicateSupportAreas() {
        Organization organization = organizationWithFounder("CODE1234");
        when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());
        when(organizationRepository.save(any(Organization.class))).thenAnswer(call -> call.getArgument(0));

        var response = organizationService.update(ORGANIZATION_ID, new UpdateOrganizationRequest(
                FOUNDER_ID, "Döngü", null, null, null, null, null, null, null, null, null,
                java.util.Arrays.asList("Finans", "   ", "Finans", null, "Pazarlama")));

        assertThat(response.supportAreas()).containsExactly("Finans", "Pazarlama");
    }

    private static Organization organizationWithFounder(String inviteCode) {
        AppUser founder = user(FOUNDER_ID, UserRole.MENTEE, UserAccountStatus.APPROVED);
        Organization organization = new Organization();
        organization.setId(ORGANIZATION_ID);
        organization.setName("GreenLoop");
        organization.setFounder(founder);
        organization.setInviteCode(inviteCode);
        return organization;
    }

    private static AppUser user(UUID id, UserRole role, UserAccountStatus status) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(id + "@demo.com");
        user.setFullName("Demo");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
