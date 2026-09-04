package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dps.teknokent.domain.StartupMaturity;
import com.dps.teknokent.domain.StartupProgramLevel;
import com.dps.teknokent.domain.StartupSector;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.RegisterFounderRequest;
import com.dps.teknokent.dto.requests.RegisterMemberRequest;
import com.dps.teknokent.dto.requests.RegisterMentorRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.OnboardingDraftRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.FirebaseIdentity;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000811");
    private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void registerFounder_keepsTheStartupProfileFromSignup() {
        stubFounderSaves();

        registrationService.registerFounder(new RegisterFounderRequest(
                "Ayşe Demir", "ayse@startup.com", "Döngü", "Gönüllülük platformu", "Kurucu", "+905551112233",
                null, null, null, null,
                "dongu.co", "Gönüllüler bulunamıyor", "Beceri bazlı eşleştirme", "Elle tutulan listeler",
                "STK'lar", "SaaS", "MVP geliştiriliyor", "MVP",
                java.util.List.of("Finans", "Pazarlama")));

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository, atLeastOnce()).saveAndFlush(saved.capture());
        Organization organization = saved.getValue();
        assertThat(organization.getWebsite()).isEqualTo("dongu.co");
        assertThat(organization.getProblem()).isEqualTo("Gönüllüler bulunamıyor");
        assertThat(organization.getSolution()).isEqualTo("Beceri bazlı eşleştirme");
        assertThat(organization.getCurrentSolution()).isEqualTo("Elle tutulan listeler");
        assertThat(organization.getTargetUser()).isEqualTo("STK'lar");
        assertThat(organization.getSector()).isEqualTo(StartupSector.SAAS);
        assertThat(organization.getMaturity()).isEqualTo(StartupMaturity.MVP_IN_PROGRESS);
        assertThat(organization.getProgramLevel()).isEqualTo(StartupProgramLevel.MVP);
        assertThat(organization.getSupportAreas()).containsExactly("Finans", "Pazarlama");
    }

    @Test
    void registerFounder_leavesStartupFieldsEmptyWhenNotSupplied() {
        stubFounderSaves();

        registrationService.registerFounder(founderRequest(
                "Ayşe Demir", "ayse@startup.com", "Döngü", null, null, null, null, null, null, null));

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository, atLeastOnce()).saveAndFlush(saved.capture());
        Organization organization = saved.getValue();
        assertThat(organization.getWebsite()).isNull();
        assertThat(organization.getSector()).isNull();
        assertThat(organization.getSupportAreas()).isEmpty();
    }

    @Test
    void registerFounder_rejectsAnUnknownSector() {
        assertThatThrownBy(() -> registrationService.registerFounder(new RegisterFounderRequest(
                "Ayşe Demir", "ayse@startup.com", "Döngü", null, null, null, null, null, null, null,
                null, null, null, null, null, "Uzay Sektörü", null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Uzay Sektörü");
    }

    private static RegisterFounderRequest founderRequest(String fullName, String email, String organizationName,
            String description, String jobTitle, String phone, UUID programId, String bio, String linkedinUrl,
            java.util.List<String> expertise) {
        return new RegisterFounderRequest(fullName, email, organizationName, description, jobTitle, phone, programId,
                bio, linkedinUrl, expertise, null, null, null, null, null, null, null, null, null);
    }

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private MentorProfileRepository mentorProfileRepository;

    @Mock
    private ProgramRepository programRepository;

    @Mock
    private ProgramStartupRepository programStartupRepository;

    @Mock
    private OnboardingDraftRepository onboardingDraftRepository;

    @InjectMocks
    private RegistrationService registrationService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registerFounder_createsPendingMenteeAndLinksOrganization() {
        when(appUserRepository.existsByEmailIgnoreCase("ayse@startup.com")).thenReturn(false);
        when(organizationRepository.saveAndFlush(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            if (organization.getId() == null) {
                organization.setId(ORGANIZATION_ID);
            }
            return organization;
        });
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });

        var result = registrationService.registerFounder(founderRequest(
                "Ayşe Demir",
                "ayse@startup.com",
                "GreenLoop",
                "Climate tech",
                "Kurucu",
                "+905551112233",
                null, null, null, null));

        assertThat(result.id()).isEqualTo(USER_ID);
        assertThat(result.email()).isEqualTo("ayse@startup.com");
        assertThat(result.fullName()).isEqualTo("Ayşe Demir");
        assertThat(result.role()).isEqualTo(UserRole.MENTEE);
        assertThat(result.status()).isEqualTo(UserAccountStatus.PENDING);
        assertThat(result.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(result.organizationName()).isEqualTo("GreenLoop");
        assertThat(result.jobTitle()).isEqualTo("Kurucu");
        verifyNoInteractions(onboardingDraftRepository);
    }

    @Test
    void registerFounder_discardsTheOnboardingDraftForTheSignedInFirebaseUser() {
        signIn(new FirebaseIdentity("uid-1", "ayse@startup.com"));
        when(appUserRepository.existsByEmailIgnoreCase("ayse@startup.com")).thenReturn(false);
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());
        when(organizationRepository.saveAndFlush(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            if (organization.getId() == null) {
                organization.setId(ORGANIZATION_ID);
            }
            return organization;
        });
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });

        registrationService.registerFounder(new RegisterFounderRequest(
                "Ayşe Demir",
                "ayse@startup.com",
                "GreenLoop",
                "Climate tech",
                "Kurucu",
                "+905551112233",
                null, null, null, null));

        verify(onboardingDraftRepository).deleteById("uid-1");
    }

    @Test
    void registerFounder_joinsAndActivatesTheProgramChosenAtSignup() {
        when(appUserRepository.existsByEmailIgnoreCase("ayse@startup.com")).thenReturn(false);
        when(organizationRepository.saveAndFlush(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            if (organization.getId() == null) {
                organization.setId(ORGANIZATION_ID);
            }
            return organization;
        });
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });
        when(programRepository.existsById(PROGRAM_ID)).thenReturn(true);

        registrationService.registerFounder(founderRequest(
                "Ayşe Demir",
                "ayse@startup.com",
                "GreenLoop",
                "Climate tech",
                "Kurucu",
                "+905551112233",
                PROGRAM_ID, null, null, null));

        ArgumentCaptor<ProgramStartup> membership = ArgumentCaptor.forClass(ProgramStartup.class);
        verify(programStartupRepository).save(membership.capture());
        assertThat(membership.getValue().getProgramId()).isEqualTo(PROGRAM_ID);
        assertThat(membership.getValue().getOrganizationId()).isEqualTo(ORGANIZATION_ID);

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getActiveProgramId()).isEqualTo(PROGRAM_ID);
    }

    // "No program" is a real answer, not a missing field.
    @Test
    void registerFounder_leavesTheProgramUnsetWhenNoneIsChosen() {
        when(appUserRepository.existsByEmailIgnoreCase("ayse@startup.com")).thenReturn(false);
        when(organizationRepository.saveAndFlush(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            if (organization.getId() == null) {
                organization.setId(ORGANIZATION_ID);
            }
            return organization;
        });
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });

        registrationService.registerFounder(founderRequest(
                "Ayşe Demir", "ayse@startup.com", "GreenLoop", "Climate tech", "Kurucu", "+905551112233", null,
                null, null, null));

        verifyNoInteractions(programStartupRepository);
    }

    @Test
    void registerFounder_rejectsAProgramThatDoesNotExist() {
        when(appUserRepository.existsByEmailIgnoreCase("ayse@startup.com")).thenReturn(false);
        when(organizationRepository.saveAndFlush(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            if (organization.getId() == null) {
                organization.setId(ORGANIZATION_ID);
            }
            return organization;
        });
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });
        when(programRepository.existsById(PROGRAM_ID)).thenReturn(false);

        assertThatThrownBy(() -> registrationService.registerFounder(founderRequest(
                "Ayşe Demir", "ayse@startup.com", "GreenLoop", "Climate tech", "Kurucu", "+905551112233",
                PROGRAM_ID, null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not exist");
        verifyNoInteractions(programStartupRepository);
    }

    @Test
    void registerFounder_keepsTheBioLinkedInAndExpertiseFromSignup() {
        stubFounderSaves();

        registrationService.registerFounder(founderRequest(
                "Ayşe Demir", "ayse@startup.com", "GreenLoop", "Climate tech", "Kurucu", "+905551112233", null,
                "Ürün odaklı kurucu", "linkedin.com/in/ayse",
                java.util.List.of("Ürün Yönetimi", "PyTorch")));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository, atLeastOnce()).saveAndFlush(saved.capture());
        AppUser founder = saved.getValue();
        assertThat(founder.getBio()).isEqualTo("Ürün odaklı kurucu");
        assertThat(founder.getLinkedinUrl()).isEqualTo("linkedin.com/in/ayse");
        assertThat(founder.getExpertise()).containsExactly("Ürün Yönetimi", "PyTorch");
    }

    // A free-form chip list can arrive with blanks and repeats; neither belongs
    // in the database.
    @Test
    void registerFounder_dropsBlankAndDuplicateExpertise() {
        stubFounderSaves();

        registrationService.registerFounder(founderRequest(
                "Ayşe Demir", "ayse@startup.com", "GreenLoop", null, null, null, null, null, null,
                java.util.Arrays.asList("Ürün", "   ", "Ürün", null, "Analitik")));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getExpertise()).containsExactly("Ürün", "Analitik");
    }

    @Test
    void registerFounder_leavesProfileFieldsEmptyWhenNotSupplied() {
        stubFounderSaves();

        registrationService.registerFounder(founderRequest(
                "Ayşe Demir", "ayse@startup.com", "GreenLoop", null, null, null, null, null, null, null));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getBio()).isNull();
        assertThat(saved.getValue().getLinkedinUrl()).isNull();
        assertThat(saved.getValue().getExpertise()).isEmpty();
    }

    private void stubFounderSaves() {
        when(appUserRepository.existsByEmailIgnoreCase("ayse@startup.com")).thenReturn(false);
        when(organizationRepository.saveAndFlush(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            if (organization.getId() == null) {
                organization.setId(ORGANIZATION_ID);
            }
            return organization;
        });
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });
    }

    @Test
    void registerMentor_createsPendingMentorWithoutOrganization() {
        when(appUserRepository.existsByEmailIgnoreCase("kemal@mentor.com")).thenReturn(false);
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });
        when(mentorProfileRepository.findMaxSortOrder()).thenReturn(9);

        var result = registrationService.registerMentor(new RegisterMentorRequest(
                "Dr. Kemal Arslan",
                "kemal@mentor.com",
                "Ürün direktörü",
                null));

        assertThat(result.id()).isEqualTo(USER_ID);
        assertThat(result.role()).isEqualTo(UserRole.MENTOR);
        assertThat(result.status()).isEqualTo(UserAccountStatus.PENDING);
        assertThat(result.organizationId()).isNull();
        assertThat(result.jobTitle()).isEqualTo("Ürün direktörü");
    }

    @Test
    void registerMentor_createsTheProfileRowThatOnboardingLaterUpdates() {
        when(appUserRepository.existsByEmailIgnoreCase("kemal@mentor.com")).thenReturn(false);
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });
        when(mentorProfileRepository.findMaxSortOrder()).thenReturn(9);

        registrationService.registerMentor(new RegisterMentorRequest(
                "Dr. Kemal Arslan",
                "kemal@mentor.com",
                "Ürün direktörü",
                null));

        ArgumentCaptor<MentorProfile> captor = ArgumentCaptor.forClass(MentorProfile.class);
        verify(mentorProfileRepository).save(captor.capture());
        MentorProfile profile = captor.getValue();
        assertThat(profile.getUser().getId()).isEqualTo(USER_ID);
        assertThat(profile.getInitials()).isEqualTo("DK");
        assertThat(profile.getProfessionalTitle()).isEqualTo("Ürün direktörü");
        assertThat(profile.getSortOrder()).isEqualTo(10);
        assertThat(profile.getCompany()).isEmpty();
        assertThat(profile.getMentoringStyle()).isEmpty();
    }

    @Test
    void registerMember_approvesMenteeWhenInviteCodeMatches() {
        Organization organization = new Organization();
        organization.setId(ORGANIZATION_ID);
        organization.setName("GreenLoop");
        organization.setInviteCode("ABCD1234");

        when(appUserRepository.existsByEmailIgnoreCase("elif@startup.com")).thenReturn(false);
        when(organizationRepository.findByInviteCode("ABCD1234")).thenReturn(Optional.of(organization));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });

        var result = registrationService.registerMember(new RegisterMemberRequest(
                "Elif Demir",
                "elif@startup.com",
                "ABCD1234",
                "Ürün tasarımcısı",
                null));

        assertThat(result.role()).isEqualTo(UserRole.MENTEE);
        assertThat(result.status()).isEqualTo(UserAccountStatus.APPROVED);
        assertThat(result.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(result.organizationName()).isEqualTo("GreenLoop");
    }

    @Test
    void registerMember_rejectsUnknownInviteCode() {
        when(appUserRepository.existsByEmailIgnoreCase("elif@startup.com")).thenReturn(false);
        when(organizationRepository.findByInviteCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrationService.registerMember(new RegisterMemberRequest(
                "Elif Demir",
                "elif@startup.com",
                "NOPE",
                null,
                null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Invalid invite code");
    }

    @Test
    void registerMentor_linksSignedInFirebaseAccount() {
        signIn(new FirebaseIdentity("uid-1", "kemal@mentor.com"));

        when(appUserRepository.existsByEmailIgnoreCase("kemal@mentor.com")).thenReturn(false);
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(USER_ID);
            return user;
        });

        registrationService.registerMentor(new RegisterMentorRequest(
                "Dr. Kemal Arslan",
                "kemal@mentor.com",
                null,
                null));

        var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFirebaseUid()).isEqualTo("uid-1");
    }

    @Test
    void registerMentor_storesPhoneNumber() {
        when(appUserRepository.existsByEmailIgnoreCase("kemal@mentor.com")).thenReturn(false);
        when(appUserRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        registrationService.registerMentor(new RegisterMentorRequest(
                "Dr. Kemal Arslan",
                "kemal@mentor.com",
                null,
                "  +90 555 111 22 33  "));

        var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo("+90 555 111 22 33");
    }

    @Test
    void registerMentor_rejectsEmailThatDoesNotMatchTheToken() {
        signIn(new FirebaseIdentity("uid-1", "baskasi@mentor.com"));

        when(appUserRepository.existsByEmailIgnoreCase("kemal@mentor.com")).thenReturn(false);

        assertThatThrownBy(() -> registrationService.registerMentor(new RegisterMentorRequest(
                "Dr. Kemal Arslan",
                "kemal@mentor.com",
                null,
                null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void registerFounder_rejectsDuplicateEmail() {
        when(appUserRepository.existsByEmailIgnoreCase("ayse@startup.com")).thenReturn(true);

        assertThatThrownBy(() -> registrationService.registerFounder(founderRequest(
                "Ayşe Demir",
                "ayse@startup.com",
                "GreenLoop",
                null,
                null,
                null,
                null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("already exists");
    }

    private static void signIn(FirebaseIdentity identity) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(identity, "firebase", List.of()));
    }
}
