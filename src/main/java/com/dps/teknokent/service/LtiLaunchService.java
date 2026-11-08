package com.dps.teknokent.service;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.dps.teknokent.config.LtiProperties;
import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.requests.LtiLaunchRequest;
import com.dps.teknokent.dto.requests.LtiLoginRequest;
import com.dps.teknokent.dto.responses.LtiLaunchResponse;
import com.dps.teknokent.dto.responses.LtiLoginResponse;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursEnrollment;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
import com.dps.teknokent.repository.OfficeHoursEnrollmentRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;
import com.dps.teknokent.security.LtiLaunchClaims;
import com.dps.teknokent.security.LtiLaunchVerifier;

/**
 * The two LTI 1.3 steps behind "open office hours" in a Moodle course: the OIDC login initiation (we answer with
 * Moodle's authorization URL carrying a fresh state and nonce) and the launch (we verify Moodle's signed token and
 * record who came from which course in which role). The launch is the sign-in; no Firebase account is involved.
 */
@Service
public class LtiLaunchService {

	static final String INSTRUCTOR_ROLE = "http://purl.imsglobal.org/vocab/lis/v2/membership#Instructor";
	static final String LEARNER_ROLE = "http://purl.imsglobal.org/vocab/lis/v2/membership#Learner";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final LtiProperties properties;
	private final LtiLoginStateStore stateStore;
	private final LtiLaunchVerifier verifier;
	private final OfficeHoursUserRepository userRepository;
	private final OfficeHoursCourseRepository courseRepository;
	private final OfficeHoursEnrollmentRepository enrollmentRepository;
	private final Clock clock;

	@Autowired
	public LtiLaunchService(LtiProperties properties, LtiLoginStateStore stateStore, LtiLaunchVerifier verifier,
			OfficeHoursUserRepository userRepository, OfficeHoursCourseRepository courseRepository,
			OfficeHoursEnrollmentRepository enrollmentRepository) {
		this(properties, stateStore, verifier, userRepository, courseRepository, enrollmentRepository,
				Clock.systemUTC());
	}

	LtiLaunchService(LtiProperties properties, LtiLoginStateStore stateStore, LtiLaunchVerifier verifier,
			OfficeHoursUserRepository userRepository, OfficeHoursCourseRepository courseRepository,
			OfficeHoursEnrollmentRepository enrollmentRepository, Clock clock) {
		this.properties = properties;
		this.stateStore = stateStore;
		this.verifier = verifier;
		this.userRepository = userRepository;
		this.courseRepository = courseRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.clock = clock;
	}

	public LtiLoginResponse startLogin(LtiLoginRequest request) {
		requireConfigured();
		if (!properties.platformIssuer().equals(request.issuer())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown LTI platform.");
		}
		if (request.clientId() != null && !properties.clientId().equals(request.clientId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown LTI client.");
		}
		// Both are hints the platform may send; when it does, they must name this tool's own deployment and URL.
		if (request.deploymentId() != null && !request.deploymentId().isBlank()
				&& !properties.deploymentIdList().contains(request.deploymentId().trim())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown LTI deployment.");
		}
		if (!sameOrigin(request.targetLinkUri(), properties.toolLaunchUrl())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The launch is not for this tool.");
		}
		String state = randomToken();
		String nonce = randomToken();
		stateStore.save(state, nonce);

		UriComponentsBuilder url = UriComponentsBuilder.fromUriString(properties.platformAuthUrl())
				.queryParam("scope", "openid")
				.queryParam("response_type", "id_token")
				.queryParam("response_mode", "form_post")
				.queryParam("prompt", "none")
				.queryParam("client_id", properties.clientId())
				.queryParam("redirect_uri", properties.toolLaunchUrl())
				.queryParam("login_hint", request.loginHint())
				.queryParam("state", state)
				.queryParam("nonce", nonce);
		if (request.ltiMessageHint() != null && !request.ltiMessageHint().isBlank()) {
			url.queryParam("lti_message_hint", request.ltiMessageHint());
		}
		return new LtiLoginResponse(url.encode().build().toUriString());
	}

	@Transactional
	public LtiLaunchResponse completeLaunch(LtiLaunchRequest request) {
		requireConfigured();
		String nonce = stateStore.consume(request.state())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
						"Unknown or already used launch state."));
		LtiLaunchClaims claims = verifier.verify(request.idToken(), nonce);
		OfficeHoursRole role = roleIn(claims.roles());
		Instant now = clock.instant();

		// Insert-if-absent then read: two first launches at once both land on the same rows instead of one of
		// them failing on a unique constraint after its single-use state is already spent.
		userRepository.insertIfAbsent(UUID.randomUUID(), claims.issuer(), claims.subject(), claims.fullName(), now);
		OfficeHoursUser user = userRepository.findByIssuerAndSubject(claims.issuer(), claims.subject())
				.orElseThrow(() -> new IllegalStateException("The office-hours user was not saved."));
		user.setFullName(claims.fullName());
		if (claims.email() != null && !claims.email().isBlank()) {
			user.setEmail(claims.email().trim().toLowerCase());
		}
		user.setLastLaunchAt(now);
		userRepository.save(user);

		String title = courseTitle(claims);
		courseRepository.insertIfAbsent(UUID.randomUUID(), claims.issuer(), claims.contextId(), title, now);
		OfficeHoursCourse course = courseRepository.findByIssuerAndContextId(claims.issuer(), claims.contextId())
				.orElseThrow(() -> new IllegalStateException("The office-hours course was not saved."));
		course.setShortName(claims.contextLabel());
		course.setTitle(title);
		courseRepository.save(course);

		enrollmentRepository.insertIfAbsent(UUID.randomUUID(), user.getId(), course.getId(), role.name(), now);
		OfficeHoursEnrollment enrollment = enrollmentRepository.findByUserIdAndCourseId(user.getId(), course.getId())
				.orElseThrow(() -> new IllegalStateException("The office-hours enrolment was not saved."));
		enrollment.setRole(role);
		enrollment.setUpdatedAt(now);
		enrollmentRepository.save(enrollment);

		return new LtiLaunchResponse(user.getId(), user.getFullName(), course.getId(), course.getTitle(), role);
	}

	/** Moodle decides who is an instructor; anyone with neither role (a guest, say) is turned away. */
	static OfficeHoursRole roleIn(List<String> roles) {
		if (roles.contains(INSTRUCTOR_ROLE)) {
			return OfficeHoursRole.INSTRUCTOR;
		}
		if (roles.contains(LEARNER_ROLE)) {
			return OfficeHoursRole.STUDENT;
		}
		throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Office hours are for a course's instructors and students.");
	}

	/** The target link must point at this tool: same scheme, host and port as the configured launch URL. */
	static boolean sameOrigin(String candidate, String toolUrl) {
		try {
			URI target = URI.create(candidate.trim());
			URI tool = URI.create(toolUrl.trim());
			return target.getScheme() != null && target.getScheme().equalsIgnoreCase(tool.getScheme())
					&& target.getHost() != null && target.getHost().equalsIgnoreCase(tool.getHost())
					&& port(target) == port(tool);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static int port(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private void requireConfigured() {
		if (!properties.configured()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LTI is not configured.");
		}
	}

	private static String courseTitle(LtiLaunchClaims claims) {
		if (claims.contextTitle() != null && !claims.contextTitle().isBlank()) {
			return claims.contextTitle().trim();
		}
		return claims.contextLabel() != null && !claims.contextLabel().isBlank()
				? claims.contextLabel().trim()
				: claims.contextId();
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
