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

    @Test
    void authenticatesApprovedUserAndBacksfillsFirebaseUid() throws Exception {
        AppUser user = user(UserAccountStatus.APPROVED);
        user.setFirebaseUid(null);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com")));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());
        when(appUserRepository.findByEmailIgnoreCase("ayse@startup.com")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/users", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(user.getFirebaseUid()).isEqualTo("uid-1");
        verify(appUserRepository).save(user);

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(AppUserPrincipal.class);
        assertThat(((AppUserPrincipal) principal).id()).isEqualTo(USER_ID);
    }

    @Test
    void forbidsPendingAccount() throws Exception {
        AppUser user = user(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com")));
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
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com")));
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
    void rejectsUnknownAccountOutsideRegistration() throws Exception {
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "yeni@startup.com")));
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
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "yeni@startup.com")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/onboarding/draft", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isEqualTo(new FirebaseIdentity("uid-1", "yeni@startup.com"));
        verify(appUserRepository, never()).findByFirebaseUid(any());
    }

    @Test
    void allowsRegistrationWithoutExistingAccount() throws Exception {
        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "yeni@startup.com")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/auth/register/founder", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isEqualTo(new FirebaseIdentity("uid-1", "yeni@startup.com"));
        verify(appUserRepository, never()).findByFirebaseUid(any());
    }

    @Test
    void letsPendingAccountUploadDocuments() throws Exception {
        AppUser user = user(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com")));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("POST", "/api/documents", "Bearer good"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(AppUserPrincipal.class);
    }

    @Test
    void letsPendingMentorUpdateItsOwnProfile() throws Exception {
        AppUser mentor = mentor(UserAccountStatus.PENDING);

        when(firebaseTokenVerifier.enabled()).thenReturn(true);
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com")));
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
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com")));
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
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com")));
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
        when(firebaseTokenVerifier.verify("good")).thenReturn(Optional.of(new FirebaseIdentity("uid-1", "ayse@startup.com")));
        when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(mentee));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("PUT", "/api/mentors/" + USER_ID, "Bearer good"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
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
