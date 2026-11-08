package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirebaseBearerFilterTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000811");
    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000822");

    @Mock
    private FirebaseTokenVerifier firebaseTokenVerifier;

    @Mock
    private AppUserRepository appUserRepository;

    @InjectMocks
    private FirebaseBearerFilter filter;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ignoresRequestsWithoutBearerHeader() throws Exception {
        MockHttpServletRequest request = request("/api/users", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(firebaseTokenVerifier, never()).verify(any());
    }

    @Test
    void rejectsBearerWhenFirebaseIsNotConfigured() throws Exception {
        when(firebaseTokenVerifier.enabled()).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/users", "Bearer token"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("broken")).thenReturn(Optional.empty());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/users", "Bearer broken"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    // "Invalid token" would send the frontend to sign-in or registration during a Firebase outage.
    @Test
    void answersServiceUnavailableWhenFirebaseCannotBeAsked() throws Exception {
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good"))
                .thenThrow(new FirebaseUnavailableException(new RuntimeException("timeout")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/auth/me", "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    // A login deleted and created again in Firebase signs in with a new uid; the old one would stay behind.
    @Test
    void replacesAStaleFirebaseUidWhenTheAccountIsFoundByEmail() throws Exception {
        AppUser user = user(UserAccountStatus.APPROVED);
        user.setFirebaseUid("uid-old");

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-new", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-new")).thenReturn(Optional.empty());
        when(appUserRepository.findByEmailIgnoreCase("ayse@startup.com")).thenReturn(Optional.of(user));

        filter.doFilter(request("/api/auth/me", "Bearer good"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(user.getFirebaseUid()).isEqualTo("uid-new");
        verify(appUserRepository).save(user);
    }

    @Test
    void authenticatesApprovedUserAndBacksfillsFirebaseUid() throws Exception {
        AppUser user = user(UserAccountStatus.APPROVED);
        user.setFirebaseUid(null);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());
        when(appUserRepository.findByEmailIgnoreCase("ayse@startup.com")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/auth/me", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(user.getFirebaseUid()).isEqualTo("uid-1");
        verify(appUserRepository).save(user);

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(AppUserPrincipal.class);
        assertThat(((AppUserPrincipal) principal).id()).isEqualTo(USER_ID);
    }

    // Firebase lets anyone create an unverified login for an address that has none yet (E1).
    @Test
    void neverHandsARowToAnUnverifiedLoginThatOnlyMatchesByEmail() throws Exception {
        AppUser user = user(UserAccountStatus.APPROVED);
        user.setFirebaseUid(null);
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good"))
                .thenReturn(Optional.of(new FirebaseIdentity("uid-stranger", "ayse@startup.com", false)));
        when(appUserRepository.findByFirebaseUid("uid-stranger")).thenReturn(Optional.empty());
        when(appUserRepository.findByEmailIgnoreCase("ayse@startup.com")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/api/auth/me", "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(user.getFirebaseUid()).isNull();
        verify(appUserRepository, never()).findByEmailIgnoreCase(any());
        verify(appUserRepository, never()).save(any());
    }

    // Seeded coordinators are created unverified and bound by uid; the check only guards the email fallback.
    @Test
    void anUnverifiedLoginAlreadyBoundByUidStillSignsIn() throws Exception {
        AppUser user = user(UserAccountStatus.APPROVED);
        user.setFirebaseUid("uid-1");
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good"))
                .thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", false)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/api/auth/me", "Bearer good"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOf(AppUserPrincipal.class);
    }

    @Test
    void neverHandsAnAdminOrCoordinatorRowToALoginThatOnlyMatchesByEmail() throws Exception {
        for (UserRole role : new UserRole[] { UserRole.ADMIN, UserRole.COORDINATOR }) {
            AppUser privileged = user(UserAccountStatus.APPROVED);
            privileged.setRole(role);
            privileged.setFirebaseUid(null);
            when(firebaseTokenVerifier.enabled()).thenReturn(true);
            when(firebaseTokenVerifier.verify("good"))
                    .thenReturn(Optional.of(new FirebaseIdentity("uid-stranger", "ayse@startup.com", true)));
            when(appUserRepository.findByFirebaseUid("uid-stranger")).thenReturn(Optional.empty());
            when(appUserRepository.findByEmailIgnoreCase("ayse@startup.com")).thenReturn(Optional.of(privileged));

            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request("/api/users", "Bearer good"), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
            assertThat(privileged.getFirebaseUid()).isNull();
            SecurityContextHolder.clearContext();
        }
        verify(appUserRepository, never()).save(any());
    }

    @Test
    void anAdminLinkedByUidSignsIn() throws Exception {
        AppUser admin = user(UserAccountStatus.APPROVED);
        admin.setRole(UserRole.ADMIN);
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "it@menton.test", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(admin));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/api/auth/me", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(((AppUserPrincipal) principal).role()).isEqualTo(UserRole.ADMIN);
    }

    // Endpoints off the browser's own paths trust ids sent in the request, so a token, even an
    // admin's, must not reach them directly; the proxy sets identity from its session instead.
    @Test
    void refusesATokenOutsideTheBrowserPaths() throws Exception {
        for (UserRole role : new UserRole[] { UserRole.MENTEE, UserRole.ADMIN }) {
            AppUser user = user(UserAccountStatus.APPROVED);
            user.setRole(role);
            signsInAs(user);
            for (String[] call : new String[][] {
                    { "GET", "/api/users" },
                    { "GET", "/api/users/" + OTHER_USER_ID + "/profile" },
                    { "PATCH", "/api/users/" + OTHER_USER_ID + "/status" },
                    { "GET", "/api/meetings" },
                    { "DELETE", "/api/meetings/" + OTHER_USER_ID },
                    { "GET", "/api/profile-analyses/latest" } }) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                MockFilterChain chain = new MockFilterChain();

                filter.doFilter(request(call[0], call[1], "Bearer good"), response, chain);

                assertThat(response.getStatus()).as(role + " " + call[0] + " " + call[1]).isEqualTo(403);
                assertThat(chain.getRequest()).isNull();
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void letsATokenReachTheBrowserPaths() throws Exception {
        signsInAs(user(UserAccountStatus.APPROVED));
        for (String[] call : new String[][] {
                { "GET", "/api/auth/me" },
                { "GET", "/api/documents" },
                { "POST", "/api/documents" },
                { "POST", "/api/profile-analyses" },
                { "POST", "/api/profile-analyses/preview" },
                { "GET", "/api/notifications" },
                { "POST", "/api/notifications/read-all" },
                { "GET", "/api/users/" + USER_ID + "/profile" },
                { "PUT", "/api/users/" + USER_ID + "/profile" } }) {
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request(call[0], call[1], "Bearer good"), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(call[0] + " " + call[1]).isNotNull();
            SecurityContextHolder.clearContext();
        }
    }

    private void signsInAs(AppUser user) {
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", user.getEmail(), true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));
    }

    @Test
    void forbidsPendingAccount() throws Exception {
        AppUser user = user(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/users", "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void letsPendingAccountReadItsOwnStatus() throws Exception {
        AppUser user = user(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/auth/me", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(AppUserPrincipal.class);
        assertThat(((AppUserPrincipal) principal).status()).isEqualTo(UserAccountStatus.PENDING);
    }

    @Test
    void letsPendingAccountSaveItsLanguage() throws Exception {
        signsInAs(user(UserAccountStatus.PENDING));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("PUT", "/api/auth/me/language", "Bearer good"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsUnknownAccountOutsideRegistration() throws Exception {
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "yeni@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());
        when(appUserRepository.findByEmailIgnoreCase("yeni@startup.com")).thenReturn(Optional.empty());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/users", "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowsOnboardingDraftWithoutExistingAccount() throws Exception {
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "yeni@startup.com", true)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/onboarding/draft", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isEqualTo(new FirebaseIdentity("uid-1", "yeni@startup.com", true));
        verify(appUserRepository, never()).findByFirebaseUid(any());
    }

    @Test
    void allowsRegistrationWithoutExistingAccount() throws Exception {
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "yeni@startup.com", true)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/auth/register/founder", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isEqualTo(new FirebaseIdentity("uid-1", "yeni@startup.com", true));
        verify(appUserRepository, never()).findByFirebaseUid(any());
    }

    @Test
    void letsPendingAccountUploadDocuments() throws Exception {
        AppUser user = user(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("POST", "/api/documents", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(AppUserPrincipal.class);
    }

    @Test
    void letsPendingAccountParseItsUploadedCv() throws Exception {
        AppUser user = mentor(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(
                request("POST", "/api/profile-analyses/preview/documents/7f1c0f5e-0000-4000-8000-000000000001", "Bearer good"),
                response,
                chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOf(AppUserPrincipal.class);
    }

    @Test
    void stillForbidsPendingAccountOtherProfileAnalysisWrites() throws Exception {
        AppUser user = mentor(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("POST", "/api/profile-analyses/manual", "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void letsPendingAccountListItsOwnDocuments() throws Exception {
        AppUser user = user(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET", "/api/documents", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(AppUserPrincipal.class);
    }

    @Test
    void letsPendingMentorUpdateItsOwnProfile() throws Exception {
        AppUser mentor = mentor(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("PUT", "/api/mentors/" + USER_ID, "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void forbidsPendingMentorUpdatingSomebodyElsesProfile() throws Exception {
        AppUser mentor = mentor(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("PUT", "/api/mentors/" + OTHER_USER_ID, "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void carveOutIsLimitedToWrites() throws Exception {
        AppUser mentor = mentor(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET", "/api/mentors/" + USER_ID, "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void carveOutDoesNotApplyToMentees() throws Exception {
        AppUser mentee = user(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentee));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("PUT", "/api/mentors/" + USER_ID, "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void letsPendingMentorSaveItsOwnAvailabilitySettingsOnly() throws Exception {
        AppUser mentor = mentor(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentor));

        MockFilterChain own = new MockFilterChain();
        filter.doFilter(request("PUT", "/api/mentors/" + USER_ID + "/availability-settings", "Bearer good"),
                new MockHttpServletResponse(), own);
        assertThat(own.getRequest()).isNotNull();

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain other = new MockFilterChain();
        filter.doFilter(request("PUT", "/api/mentors/" + OTHER_USER_ID + "/availability-settings", "Bearer good"),
                response, other);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(other.getRequest()).isNull();
    }

    @Test
    void letsPendingAccountUpdateItsOwnUserProfileOnly() throws Exception {
        AppUser mentee = user(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com", true)));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentee));

        MockFilterChain own = new MockFilterChain();
        filter.doFilter(request("PUT", "/api/users/" + USER_ID + "/profile", "Bearer good"),
                new MockHttpServletResponse(), own);
        assertThat(own.getRequest()).isNotNull();

        MockFilterChain read = new MockFilterChain();
        filter.doFilter(request("GET", "/api/users/" + USER_ID + "/profile", "Bearer good"),
                new MockHttpServletResponse(), read);
        assertThat(read.getRequest()).isNotNull();

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain other = new MockFilterChain();
        filter.doFilter(request("PUT", "/api/users/" + OTHER_USER_ID + "/profile", "Bearer good"), response, other);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(other.getRequest()).isNull();
    }

    private static MockHttpServletRequest request(String uri, String authorization) {
        return request("GET", uri, authorization);
    }

    private static MockHttpServletRequest request(String method, String uri, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private static AppUser mentor(UserAccountStatus status) {
        AppUser mentor = user(status);
        mentor.setRole(UserRole.MENTOR);
        return mentor;
    }

    private static AppUser user(UserAccountStatus status) {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setEmail("ayse@startup.com");
        user.setFullName("Ayşe Demir");
        user.setRole(UserRole.MENTEE);
        user.setStatus(status);
        user.setFirebaseUid("uid-1");
        return user;
    }
}
