package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.dps.teknokent.config.LtiProperties;
import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.requests.LtiLaunchRequest;
import com.dps.teknokent.dto.requests.LtiLoginRequest;
import com.dps.teknokent.dto.responses.LtiLaunchResponse;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursEnrollment;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
import com.dps.teknokent.repository.OfficeHoursEnrollmentRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;
import com.dps.teknokent.security.LtiLaunchClaims;
import com.dps.teknokent.security.LtiLaunchVerifier;

@ExtendWith(MockitoExtension.class)
class LtiLaunchServiceTest {

    private static final String ISSUER = "http://localhost:8081";
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    @Mock
    private LtiLoginStateStore stateStore;
    @Mock
    private LtiLaunchVerifier verifier;
    @Mock
    private OfficeHoursUserRepository userRepository;
    @Mock
    private OfficeHoursCourseRepository courseRepository;
    @Mock
    private OfficeHoursEnrollmentRepository enrollmentRepository;

    private LtiLaunchService service;

    @BeforeEach
    void setUp() {
        LtiProperties properties = new LtiProperties(ISSUER, "client-1", "1", ISSUER + "/mod/lti/auth.php",
                ISSUER + "/mod/lti/certs.php", "http://localhost:3000/lti/launch", null);
        service = new LtiLaunchService(properties, stateStore, verifier, userRepository, courseRepository,
                enrollmentRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LtiLaunchClaims claims(String role) {
        return new LtiLaunchClaims(ISSUER, "42", "Ayşe Yılmaz", "Ayse@METU.edu.tr", "7", "CENG 101",
                "Introduction to Computing", List.of(role));
    }

    @Test
    void sendsTheBrowserToMoodleWithAFreshStateAndNonce() {
        String url = service.startLogin(new LtiLoginRequest(ISSUER, "hint-1", "http://localhost:3000/lti/launch",
                "message-hint", "client-1", "1")).redirectUrl();

        Map<String, String> query = UriComponentsBuilder.fromUri(URI.create(url)).build().getQueryParams()
                .toSingleValueMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()));
        assertThat(url).startsWith(ISSUER + "/mod/lti/auth.php?");
        assertThat(query).containsEntry("scope", "openid")
                .containsEntry("response_type", "id_token")
                .containsEntry("response_mode", "form_post")
                .containsEntry("prompt", "none")
                .containsEntry("client_id", "client-1")
                .containsEntry("login_hint", "hint-1")
                .containsEntry("lti_message_hint", "message-hint");
        assertThat(query.get("redirect_uri")).contains("localhost:3000");
        verify(stateStore).save(query.get("state"), query.get("nonce"));
        assertThat(query.get("state")).isNotEqualTo(query.get("nonce")).hasSizeGreaterThan(20);
    }

    @Test
    void refusesALoginFromAnotherMoodleOrClient() {
        assertThatThrownBy(() -> service.startLogin(
                new LtiLoginRequest("https://other.example", "hint", "http://localhost:3000/lti/launch", null, null,
                        null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.startLogin(
                new LtiLoginRequest(ISSUER, "hint", "http://localhost:3000/lti/launch", null, "client-2", null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(stateStore, never()).save(anyString(), anyString());
    }

    @Test
    void recordsALaunchByInsertingIfAbsentSoTwoFirstLaunchesBothSucceed() {
        when(stateStore.consume("state-1")).thenReturn(Optional.of("nonce-1"));
        when(verifier.verify("token", "nonce-1")).thenReturn(claims(LtiLaunchService.LEARNER_ROLE));
        OfficeHoursUser inserted = new OfficeHoursUser();
        inserted.setId(UUID.randomUUID());
        OfficeHoursCourse insertedCourse = new OfficeHoursCourse();
        insertedCourse.setId(UUID.randomUUID());
        OfficeHoursEnrollment insertedEnrollment = new OfficeHoursEnrollment();
        when(userRepository.findByIssuerAndSubject(ISSUER, "42")).thenReturn(Optional.of(inserted));
        when(courseRepository.findByIssuerAndContextId(ISSUER, "7")).thenReturn(Optional.of(insertedCourse));
        when(enrollmentRepository.findByUserIdAndCourseId(inserted.getId(), insertedCourse.getId()))
                .thenReturn(Optional.of(insertedEnrollment));

        LtiLaunchResponse response = service.completeLaunch(new LtiLaunchRequest("token", "state-1"));

        verify(userRepository).insertIfAbsent(any(), eq(ISSUER), eq("42"), eq("Ayşe Yılmaz"), eq(NOW));
        verify(courseRepository).insertIfAbsent(any(), eq(ISSUER), eq("7"), eq("Introduction to Computing"), eq(NOW));
        verify(enrollmentRepository).insertIfAbsent(any(), eq(inserted.getId()), eq(insertedCourse.getId()),
                eq("STUDENT"), eq(NOW));
        assertThat(inserted.getEmail()).isEqualTo("ayse@metu.edu.tr");
        assertThat(inserted.getLastLaunchAt()).isEqualTo(NOW);
        assertThat(insertedCourse.getShortName()).isEqualTo("CENG 101");
        assertThat(insertedEnrollment.getRole()).isEqualTo(OfficeHoursRole.STUDENT);

        assertThat(response.role()).isEqualTo(OfficeHoursRole.STUDENT);
        assertThat(response.userId()).isEqualTo(inserted.getId());
        assertThat(response.courseId()).isEqualTo(insertedCourse.getId());
        assertThat(response.fullName()).isEqualTo("Ayşe Yılmaz");
    }

    @Test
    void updatesAKnownPersonAndTheirRoleOnTheNextLaunch() {
        OfficeHoursUser known = new OfficeHoursUser();
        known.setId(UUID.randomUUID());
        known.setFullName("Old Name");
        OfficeHoursCourse course = new OfficeHoursCourse();
        course.setId(UUID.randomUUID());
        OfficeHoursEnrollment enrollment = new OfficeHoursEnrollment();
        enrollment.setRole(OfficeHoursRole.STUDENT);
        when(stateStore.consume("state-1")).thenReturn(Optional.of("nonce-1"));
        when(verifier.verify("token", "nonce-1")).thenReturn(claims(LtiLaunchService.INSTRUCTOR_ROLE));
        when(userRepository.findByIssuerAndSubject(ISSUER, "42")).thenReturn(Optional.of(known));
        when(courseRepository.findByIssuerAndContextId(ISSUER, "7")).thenReturn(Optional.of(course));
        when(enrollmentRepository.findByUserIdAndCourseId(known.getId(), course.getId()))
                .thenReturn(Optional.of(enrollment));

        LtiLaunchResponse response = service.completeLaunch(new LtiLaunchRequest("token", "state-1"));

        assertThat(known.getFullName()).isEqualTo("Ayşe Yılmaz");
        assertThat(known.getLastLaunchAt()).isEqualTo(NOW);
        assertThat(enrollment.getRole()).isEqualTo(OfficeHoursRole.INSTRUCTOR);
        assertThat(response.userId()).isEqualTo(known.getId());
        assertThat(response.role()).isEqualTo(OfficeHoursRole.INSTRUCTOR);
    }

    @Test
    void refusesAStateThatIsUnknownOrAlreadyUsed() {
        when(stateStore.consume("used")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeLaunch(new LtiLaunchRequest("token", "used")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(verifier, never()).verify(anyString(), anyString());
    }

    @Test
    void turnsAwaySomeoneWithNoCourseRole() {
        when(stateStore.consume("state-1")).thenReturn(Optional.of("nonce-1"));
        when(verifier.verify(eq("token"), eq("nonce-1")))
                .thenReturn(claims("http://purl.imsglobal.org/vocab/lis/v2/institution/person#Guest"));

        assertThatThrownBy(() -> service.completeLaunch(new LtiLaunchRequest("token", "state-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    void refusesALoginForAnotherDeploymentOrAnotherTool() {
        assertThatThrownBy(() -> service.startLogin(new LtiLoginRequest(ISSUER, "hint",
                "http://localhost:3000/lti/launch", null, "client-1", "9")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.startLogin(new LtiLoginRequest(ISSUER, "hint",
                "https://evil.example/lti/launch", null, "client-1", "1")))
                .isInstanceOf(ResponseStatusException.class);
        verify(stateStore, never()).save(anyString(), anyString());
    }

    @Test
    void acceptsAnyPathOnTheToolsOwnOrigin() {
        assertThat(LtiLaunchService.sameOrigin("http://localhost:3000/office-hours", "http://localhost:3000/lti/launch"))
                .isTrue();
        assertThat(LtiLaunchService.sameOrigin("https://menton.example/x", "https://menton.example/lti/launch"))
                .isTrue();
        assertThat(LtiLaunchService.sameOrigin("http://localhost:3001/lti/launch", "http://localhost:3000/lti/launch"))
                .isFalse();
        assertThat(LtiLaunchService.sameOrigin("not a uri", "http://localhost:3000/lti/launch")).isFalse();
    }

    @Test
    void anInstructorRoleWinsOverALearnerRole() {
        assertThat(LtiLaunchService.roleIn(List.of(LtiLaunchService.LEARNER_ROLE, LtiLaunchService.INSTRUCTOR_ROLE)))
                .isEqualTo(OfficeHoursRole.INSTRUCTOR);
    }

    @Test
    void saysLtiIsUnavailableWhenNotConfigured() {
        LtiLaunchService unconfigured = new LtiLaunchService(new LtiProperties(null, null, null, null, null, null,
                null), stateStore, verifier, userRepository, courseRepository, enrollmentRepository);

        assertThatThrownBy(() -> unconfigured.startLogin(new LtiLoginRequest(ISSUER, "h", "t", null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(
                        exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
