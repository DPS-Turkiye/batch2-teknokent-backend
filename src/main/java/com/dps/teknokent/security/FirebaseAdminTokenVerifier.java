package com.dps.teknokent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.dps.teknokent.config.FirebaseApps;
import com.dps.teknokent.config.FirebaseProperties;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

public class FirebaseAdminTokenVerifier implements FirebaseTokenVerifier {

	/**
	 * How long a token stays trusted after Firebase confirmed it was not revoked and its account not disabled.
	 * Each check is a Firebase lookup and the notification bell polls, so revocation takes effect within this.
	 */
	static final Duration REVOCATION_CHECK_TTL = Duration.ofSeconds(60);

	// ponytail: expired entries are dropped only past this size, at most once per TTL; a cache library if memory
	// ever matters.
	private static final int PRUNE_ABOVE = 10_000;

	private final FirebaseAuth firebaseAuth;
	private final Clock clock;
	private final Map<String, Checked> checked = new ConcurrentHashMap<>();
	private volatile Instant lastPrune = Instant.EPOCH;

	private record Checked(FirebaseIdentity identity, Instant until) {
	}

	public FirebaseAdminTokenVerifier(FirebaseProperties properties) {
		this(FirebaseAuth.getInstance(FirebaseApps.named(properties)), Clock.systemUTC());
	}

	FirebaseAdminTokenVerifier(FirebaseAuth firebaseAuth, Clock clock) {
		this.firebaseAuth = firebaseAuth;
		this.clock = clock;
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public Optional<FirebaseIdentity> verify(String idToken) {
		if (idToken == null || idToken.isBlank()) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		String key = sha256(idToken);
		Checked hit = checked.get(key);
		if (hit != null && now.isBefore(hit.until())) {
			return Optional.of(hit.identity());
		}
		try {
			FirebaseToken decoded = firebaseAuth.verifyIdToken(idToken, true);
			String email = decoded.getEmail();
			if (email == null || email.isBlank()) {
				return Optional.empty();
			}
			FirebaseIdentity identity = new FirebaseIdentity(decoded.getUid(), email, decoded.isEmailVerified());
			remember(key, new Checked(identity, trustedUntil(decoded, now)), now);
			return Optional.of(identity);
		} catch (FirebaseAuthException exception) {
			checked.remove(key);
			if (exception.getAuthErrorCode() == null
					|| exception.getAuthErrorCode() == AuthErrorCode.CERTIFICATE_FETCH_FAILED) {
				throw new FirebaseUnavailableException(exception);
			}
			return Optional.empty();
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	/** Never past the token's own expiry. */
	private static Instant trustedUntil(FirebaseToken decoded, Instant now) {
		Instant until = now.plus(REVOCATION_CHECK_TTL);
		if (decoded.getClaims() != null && decoded.getClaims().get("exp") instanceof Number seconds) {
			Instant expires = Instant.ofEpochSecond(seconds.longValue());
			return expires.isBefore(until) ? expires : until;
		}
		return until;
	}

	private void remember(String key, Checked entry, Instant now) {
		if (checked.size() > PRUNE_ABOVE && !now.isBefore(lastPrune.plus(REVOCATION_CHECK_TTL))) {
			lastPrune = now;
			checked.values().removeIf(old -> !now.isBefore(old.until()));
		}
		checked.put(key, entry);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
