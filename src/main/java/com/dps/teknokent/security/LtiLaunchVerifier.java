package com.dps.teknokent.security;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.LtiProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Checks an LTI 1.3 launch token the way the spec asks: RS256 signature from Moodle's key set, then iss, aud,
 * azp, exp, iat, the single-use nonce, the deployment, the message type and the LTI version. Anything wrong is
 * a 401 with a reason meant for logs, never for the person launching.
 */
@Component
public class LtiLaunchVerifier {

	static final String CLAIM_PREFIX = "https://purl.imsglobal.org/spec/lti/claim/";
	private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

	private final LtiProperties properties;
	private final LtiPlatformKeys platformKeys;
	private final Clock clock;

	@Autowired
	public LtiLaunchVerifier(LtiProperties properties, LtiPlatformKeys platformKeys) {
		this(properties, platformKeys, Clock.systemUTC());
	}

	LtiLaunchVerifier(LtiProperties properties, LtiPlatformKeys platformKeys, Clock clock) {
		this.properties = properties;
		this.platformKeys = platformKeys;
		this.clock = clock;
	}

	public LtiLaunchClaims verify(String idToken, String expectedNonce) {
		if (!properties.configured() || platformKeys.source() == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LTI is not configured.");
		}
		JWTClaimsSet claims = verifiedClaims(idToken);
		Instant now = clock.instant();

		require(properties.platformIssuer().equals(claims.getIssuer()), "Unexpected issuer.");
		List<String> audience = claims.getAudience();
		require(audience != null && audience.contains(properties.clientId()), "The token is not for this tool.");
		Object azp = claims.getClaim("azp");
		if (audience.size() > 1 || azp != null) {
			require(properties.clientId().equals(azp), "Unexpected authorized party.");
		}
		Date expires = claims.getExpirationTime();
		require(expires != null && now.minus(CLOCK_SKEW).isBefore(expires.toInstant()), "The token has expired.");
		Date issued = claims.getIssueTime();
		require(issued != null && !issued.toInstant().isAfter(now.plus(CLOCK_SKEW)), "The token is issued in the future.");
		require(expectedNonce != null && expectedNonce.equals(claims.getClaim("nonce")), "The nonce does not match.");
		require(properties.deploymentIdList().contains(stringClaim(claims, CLAIM_PREFIX + "deployment_id")),
				"Unknown deployment.");
		require("LtiResourceLinkRequest".equals(stringClaim(claims, CLAIM_PREFIX + "message_type")),
				"Only resource-link launches open office hours.");
		require("1.3.0".equals(stringClaim(claims, CLAIM_PREFIX + "version")), "Only LTI 1.3 is supported.");
		String subject = claims.getSubject();
		require(subject != null && !subject.isBlank(), "The launch has no user.");

		Map<String, Object> context = mapClaim(claims, CLAIM_PREFIX + "context");
		String contextId = context == null ? null : (String) context.get("id");
		require(contextId != null && !contextId.isBlank(), "Office hours open from a course only.");

		return new LtiLaunchClaims(
				claims.getIssuer(),
				subject,
				fullName(claims),
				stringClaim(claims, "email"),
				contextId,
				(String) context.get("label"),
				(String) context.get("title"),
				roles(claims));
	}

	private JWTClaimsSet verifiedClaims(String idToken) {
		DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, platformKeys.source()));
		// Claims are checked below against our own clock and settings, so the processor only checks the signature.
		processor.setJWTClaimsSetVerifier((claims, context) -> {
		});
		try {
			return processor.process(idToken, null);
		} catch (ParseException | BadJOSEException | JOSEException exception) {
			throw unauthorized("The launch token could not be verified.");
		}
	}

	private static String fullName(JWTClaimsSet claims) {
		String name = stringClaim(claims, "name");
		if (name != null && !name.isBlank()) {
			return name.trim();
		}
		String given = stringClaim(claims, "given_name");
		String family = stringClaim(claims, "family_name");
		String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
		return joined.isEmpty() ? claims.getSubject() : joined;
	}

	private static List<String> roles(JWTClaimsSet claims) {
		Object roles = claims.getClaim(CLAIM_PREFIX + "roles");
		if (!(roles instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
	}

	private static String stringClaim(JWTClaimsSet claims, String name) {
		Object value = claims.getClaim(name);
		return value instanceof String text ? text : null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapClaim(JWTClaimsSet claims, String name) {
		Object value = claims.getClaim(name);
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

	private static void require(boolean condition, String reason) {
		if (!condition) {
			throw unauthorized(reason);
		}
	}

	private static ResponseStatusException unauthorized(String reason) {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
	}
}
