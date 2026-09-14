package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.DecideAccountStatusRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationMemberRequest;
import com.dps.teknokent.dto.requests.UpdateUserProfileRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.InviteCodeGenerator;

@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @Mock
    private InviteCodeGenerator inviteCodeGenerator;

    @Mock
    private NotificationService notificationService;

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

    @Test
    void findByOrganization_returnsMembersSortedByFullName() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000501");
        Organization organization = organization(organizationId, "GreenLoop");
        AppUser member = user(
                UUID.fromString("00000000-0000-0000-0000-000000000502"), "ekip@greenloop.demo", "Ekip Üyesi",
                UserRole.MENTEE, organization, "Ürün Müdürü");

        organization.setFounder(member);
        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId)).thenReturn(List.of(member));

        var result = appUserService.findByOrganization(organizationId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fullName()).isEqualTo("Ekip Üyesi");
        assertThat(result.get(0).organizationId()).isEqualTo(organizationId);
        assertThat(result.get(0).founder()).isTrue();
    }

    @Test
    void addOrganizationMember_createsMentaeTiedToTheOrganization() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        Organization organization = organization(organizationId, "GreenLoop");
        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(appUserRepository.existsByEmailIgnoreCase("yeni.uye@greenloop.demo")).thenReturn(false);
        when(appUserRepository.save(org.mockito.ArgumentMatchers.any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = appUserService.addOrganizationMember(
                organizationId,
                new CreateOrganizationMemberRequest("Yeni Üye", "yeni.uye@greenloop.demo", "Ürün Yöneticisi"));

        assertThat(result.fullName()).isEqualTo("Yeni Üye");
        assertThat(result.email()).isEqualTo("yeni.uye@greenloop.demo");
        assertThat(result.role()).isEqualTo(UserRole.MENTEE);
        assertThat(result.status()).isEqualTo(UserAccountStatus.APPROVED);
        assertThat(result.organizationId()).isEqualTo(organizationId);
    }

    @Test
    void addOrganizationMember_rejectsDuplicateEmail() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000701");
        Organization organization = organization(organizationId, "GreenLoop");
        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(appUserRepository.existsByEmailIgnoreCase("mevcut@greenloop.demo")).thenReturn(true);

        assertThatThrownBy(() -> appUserService.addOrganizationMember(
                organizationId,
                new CreateOrganizationMemberRequest("Tekrar", "mevcut@greenloop.demo", null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("already exists");
    }

    private static final UUID TEAM_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID TEAM_FOUNDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID TEAM_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000903");

    private static Organization teamWithFounder() {
        Organization organization = organization(TEAM_ORG_ID, "Döngü");
        organization.setFounder(user(TEAM_FOUNDER_ID, "gulse@dongu.app", "Gülse Buran", UserRole.MENTEE,
                organization, "Product Designer"));
        return organization;
    }

    @Test
    void updateOrganizationMember_letsTheFounderEditAnotherMember() {
        Organization organization = teamWithFounder();
        AppUser member = user(TEAM_MEMBER_ID, "sude@dongu.app", "Eski Ad", UserRole.MENTEE, organization, null);
        when(domainLookupService.requireOrganization(TEAM_ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAM_MEMBER_ID)).thenReturn(member);
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = appUserService.updateOrganizationMember(TEAM_ORG_ID, TEAM_MEMBER_ID,
                new UpdateOrganizationMemberRequest(TEAM_FOUNDER_ID, "Sude Arya", "Developer"));

        assertThat(result.fullName()).isEqualTo("Sude Arya");
        assertThat(result.jobTitle()).isEqualTo("Developer");
        assertThat(result.founder()).isFalse();
    }

    @Test
    void updateOrganizationMember_letsAMemberEditThemself() {
        Organization organization = teamWithFounder();
        AppUser member = user(TEAM_MEMBER_ID, "sude@dongu.app", "Sude", UserRole.MENTEE, organization, null);
        when(domainLookupService.requireOrganization(TEAM_ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAM_MEMBER_ID)).thenReturn(member);
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = appUserService.updateOrganizationMember(TEAM_ORG_ID, TEAM_MEMBER_ID,
                new UpdateOrganizationMemberRequest(TEAM_MEMBER_ID, "Sude Arya", "Developer"));

        assertThat(result.jobTitle()).isEqualTo("Developer");
    }

    @Test
    void updateOrganizationMember_rejectsAnotherTeammate() {
        Organization organization = teamWithFounder();
        AppUser member = user(TEAM_MEMBER_ID, "sude@dongu.app", "Sude", UserRole.MENTEE, organization, null);
        UUID teammateId = UUID.fromString("00000000-0000-0000-0000-000000000904");
        when(domainLookupService.requireOrganization(TEAM_ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAM_MEMBER_ID)).thenReturn(member);

        assertThatThrownBy(() -> appUserService.updateOrganizationMember(TEAM_ORG_ID, TEAM_MEMBER_ID,
                new UpdateOrganizationMemberRequest(teammateId, "Başka Ad", null)))
                .isInstanceOf(InvalidRequestException.class);

        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void updateOrganizationMember_rejectsSomeoneFromAnotherTeam() {
        Organization organization = teamWithFounder();
        AppUser outsider = user(TEAM_MEMBER_ID, "dis@baska.app", "Dış Kişi", UserRole.MENTEE,
                organization(UUID.fromString("00000000-0000-0000-0000-000000000999"), "Başka"), null);
        when(domainLookupService.requireOrganization(TEAM_ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAM_MEMBER_ID)).thenReturn(outsider);

        assertThatThrownBy(() -> appUserService.updateOrganizationMember(TEAM_ORG_ID, TEAM_MEMBER_ID,
                new UpdateOrganizationMemberRequest(TEAM_FOUNDER_ID, "Yeni Ad", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removeOrganizationMember_detachesTheMemberFromTheTeam() {
        Organization organization = teamWithFounder();
        AppUser member = user(TEAM_MEMBER_ID, "sude@dongu.app", "Sude", UserRole.MENTEE, organization, null);
        when(domainLookupService.requireOrganization(TEAM_ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAM_MEMBER_ID)).thenReturn(member);

        appUserService.removeOrganizationMember(TEAM_ORG_ID, TEAM_MEMBER_ID, TEAM_FOUNDER_ID);

        assertThat(member.getOrganization()).isNull();
        verify(appUserRepository).save(member);
    }

    @Test
    void removeOrganizationMember_refusesToRemoveTheFounder() {
        Organization organization = teamWithFounder();
        when(domainLookupService.requireOrganization(TEAM_ORG_ID)).thenReturn(organization);

        assertThatThrownBy(() -> appUserService.removeOrganizationMember(TEAM_ORG_ID, TEAM_FOUNDER_ID,
                TEAM_FOUNDER_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("founder cannot be removed");

        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void removeOrganizationMember_rejectsAnActorWhoIsNotTheFounder() {
        Organization organization = teamWithFounder();
        when(domainLookupService.requireOrganization(TEAM_ORG_ID)).thenReturn(organization);

        assertThatThrownBy(() -> appUserService.removeOrganizationMember(TEAM_ORG_ID, TEAM_MEMBER_ID,
                TEAM_MEMBER_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Only the organization founder");

        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void findAll_filtersByAccountStatus() {
        AppUser pending = user(UUID.fromString("00000000-0000-0000-0000-000000000801"), "pending", "Ayşe Demir", UserRole.MENTEE);
        pending.setStatus(UserAccountStatus.PENDING);
        AppUser approved = user(UUID.fromString("00000000-0000-0000-0000-000000000802"), "approved", "Elif Demir", UserRole.MENTEE);

        when(appUserRepository.findAll()).thenReturn(List.of(pending, approved));

        var result = appUserService.findAll(null, UserAccountStatus.PENDING);

        assertThat(result).extracting(user -> user.fullName()).containsExactly("Ayşe Demir");
        assertThat(result.getFirst().status()).isEqualTo(UserAccountStatus.PENDING);
    }

    @Test
    void decideStatus_approvesFounderAndCreatesInviteCode() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID founderId = UUID.fromString("00000000-0000-0000-0000-000000000811");
        UUID organizationId = UUID.fromString("10000000-0000-0000-0000-000000000011");

        AppUser admin = user(adminId, "admin@teknokent.demo", "Demo Administrator", UserRole.ADMIN);
        AppUser founder = user(founderId, "ayse@startup.com", "Ayşe Demir", UserRole.MENTEE);
        founder.setStatus(UserAccountStatus.PENDING);
        Organization organization = organization(organizationId, "GreenLoop");
        organization.setFounder(founder);

        when(domainLookupService.requireUser(adminId)).thenReturn(admin);
        when(domainLookupService.requireUser(founderId)).thenReturn(founder);
        when(appUserRepository.save(founder)).thenReturn(founder);
        when(organizationRepository.findByFounderId(founderId)).thenReturn(Optional.of(organization));
        when(inviteCodeGenerator.nextUnique(any())).thenReturn("ABCD2345");
        when(organizationRepository.save(organization)).thenReturn(organization);

        var result = appUserService.decideStatus(founderId, new DecideAccountStatusRequest(adminId, UserAccountStatus.APPROVED));

        assertThat(result.user().status()).isEqualTo(UserAccountStatus.APPROVED);
        assertThat(result.inviteCode()).isEqualTo("ABCD2345");
        assertThat(organization.getInviteCode()).isEqualTo("ABCD2345");
    }

    @Test
    void decideStatus_approvesMentorWithoutInviteCode() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000812");

        AppUser admin = user(adminId, "admin@teknokent.demo", "Demo Administrator", UserRole.ADMIN);
        AppUser mentor = user(mentorId, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.MENTOR);
        mentor.setStatus(UserAccountStatus.PENDING);

        when(domainLookupService.requireUser(adminId)).thenReturn(admin);
        when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
        when(appUserRepository.save(mentor)).thenReturn(mentor);
        when(organizationRepository.findByFounderId(mentorId)).thenReturn(Optional.empty());

        var result = appUserService.decideStatus(mentorId, new DecideAccountStatusRequest(adminId, UserAccountStatus.APPROVED));

        assertThat(result.user().status()).isEqualTo(UserAccountStatus.APPROVED);
        assertThat(result.inviteCode()).isNull();
        verify(inviteCodeGenerator, never()).nextUnique(any());
    }

    @Test
    void decideStatus_rejectsPendingAccount() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000812");

        AppUser admin = user(adminId, "admin@teknokent.demo", "Demo Administrator", UserRole.ADMIN);
        AppUser mentor = user(mentorId, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.MENTOR);
        mentor.setStatus(UserAccountStatus.PENDING);

        when(domainLookupService.requireUser(adminId)).thenReturn(admin);
        when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
        when(appUserRepository.save(mentor)).thenReturn(mentor);

        var result = appUserService.decideStatus(mentorId, new DecideAccountStatusRequest(adminId, UserAccountStatus.REJECTED));

        assertThat(result.user().status()).isEqualTo(UserAccountStatus.REJECTED);
        assertThat(result.inviteCode()).isNull();
        verify(organizationRepository, never()).findByFounderId(any());
    }

    @Test
    void decideStatus_rejectsNonAdminActor() {
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000811");
        AppUser mentor = user(actorId, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.MENTOR);

        when(domainLookupService.requireUser(actorId)).thenReturn(mentor);

        assertThatThrownBy(() -> appUserService.decideStatus(
                targetId, new DecideAccountStatusRequest(actorId, UserAccountStatus.APPROVED)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Only an admin");
    }

    @Test
    void decideStatus_rejectsNonPendingTarget() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000811");
        AppUser admin = user(adminId, "admin@teknokent.demo", "Demo Administrator", UserRole.ADMIN);
        AppUser target = user(targetId, "ayse@startup.com", "Ayşe Demir", UserRole.MENTEE);

        when(domainLookupService.requireUser(adminId)).thenReturn(admin);
        when(domainLookupService.requireUser(targetId)).thenReturn(target);

        assertThatThrownBy(() -> appUserService.decideStatus(
                targetId, new DecideAccountStatusRequest(adminId, UserAccountStatus.APPROVED)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Only pending accounts");
    }

    @Test
    void decideStatus_rejectsPendingAsDecisionValue() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000811");
        AppUser admin = user(adminId, "admin@teknokent.demo", "Demo Administrator", UserRole.ADMIN);

        when(domainLookupService.requireUser(adminId)).thenReturn(admin);

        assertThatThrownBy(() -> appUserService.decideStatus(
                targetId, new DecideAccountStatusRequest(adminId, UserAccountStatus.PENDING)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("APPROVED or REJECTED");
    }

    private static AppUser user(UUID id, String email, String fullName, UserRole role) {
        return user(id, email, fullName, role, null, null);
    }

    @Test
    void updateProfile_storesTheOwnersOwnFields() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000811");
        AppUser owner = user(id, "gulse@dongu.co", "Gülse Buran", UserRole.MENTEE);
        when(domainLookupService.requireUser(id)).thenReturn(owner);
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(call -> call.getArgument(0));

        var response = appUserService.updateProfile(id, new UpdateUserProfileRequest(
                id, "Gülse Buran", "Designer", "+905551112233", "Ürün odaklı kurucu",
                "linkedin.com/in/gulse", List.of("UI/UX", "Figma")));

        assertThat(response.bio()).isEqualTo("Ürün odaklı kurucu");
        assertThat(response.linkedinUrl()).isEqualTo("linkedin.com/in/gulse");
        assertThat(response.expertise()).containsExactly("UI/UX", "Figma");
        assertThat(owner.getJobTitle()).isEqualTo("Designer");
    }

    @Test
    void updateProfile_rejectsEditingSomeoneElse() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000811");
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000201");
        when(domainLookupService.requireUser(id))
                .thenReturn(user(id, "gulse@dongu.co", "Gülse Buran", UserRole.MENTEE));

        assertThatThrownBy(() -> appUserService.updateProfile(id, new UpdateUserProfileRequest(
                other, "Gülse Buran", null, null, null, null, List.of())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("your own profile");

        verify(appUserRepository, never()).save(any(AppUser.class));
    }

    @Test
    void updateProfile_dropsBlankAndDuplicateExpertise() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000811");
        AppUser owner = user(id, "gulse@dongu.co", "Gülse Buran", UserRole.MENTEE);
        when(domainLookupService.requireUser(id)).thenReturn(owner);
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(call -> call.getArgument(0));

        var response = appUserService.updateProfile(id, new UpdateUserProfileRequest(
                id, "Gülse Buran", null, null, null, null,
                java.util.Arrays.asList("UI/UX", "   ", "UI/UX", null, "Figma")));

        assertThat(response.expertise()).containsExactly("UI/UX", "Figma");
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
