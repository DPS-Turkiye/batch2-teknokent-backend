package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

class FirebaseAdminTokenVerifierTest {

	private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

	private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
	private final SetClock clock = new SetClock();
	private final FirebaseAdminTokenVerifier verifier = new FirebaseAdminTokenVerifier(firebaseAuth, clock);

	@Test
	void asksFirebaseWhetherTheTokenWasRevokedOrTheAccountDisabled() throws Exception {
		FirebaseToken valid = token("uid-1", "qa@teknokent-dev.test");
		when(firebaseAuth.verifyIdToken("token", true)).thenReturn(valid);

		assertThat(verifier.verify("token")).contains(new FirebaseIdentity("uid-1", "qa@teknokent-dev.test", true));
		verify(firebaseAuth, never()).verifyIdToken(anyString());
	}

	@Test
	void carriesWhetherFirebaseVerifiedTheEmail() throws Exception {
		FirebaseToken unverified = token("uid-1", "qa@teknokent-dev.test");
		when(unverified.isEmailVerified()).thenReturn(false);
		when(firebaseAuth.verifyIdToken("token", true)).thenReturn(unverified);

		assertThat(verifier.verify("token")).contains(new FirebaseIdentity("uid-1", "qa@teknokent-dev.test", false));
	}

	@Test
	void refusesARevokedTokenOrDisabledAccount() throws Exception {
		for (AuthErrorCode code : new AuthErrorCode[] {AuthErrorCode.REVOKED_ID_TOKEN, AuthErrorCode.USER_DISABLED}) {
			FirebaseAuthException failure = mock(FirebaseAuthException.class);
			when(failure.getAuthErrorCode()).thenReturn(code);
			doThrow(failure).when(firebaseAuth).verifyIdToken("token", true);

			assertThat(verifier.verify("token")).isEmpty();
		}
	}

	// The bell polls, so a Firebase lookup per call would be one per user every few seconds.
	@Test
	void trustsAConfirmedTokenForSixtySecondsThenAsksAgain() throws Exception {
		FirebaseToken valid = token("uid-1", "qa@teknokent-dev.test");
		when(firebaseAuth.verifyIdToken("token", true)).thenReturn(valid);

		verifier.verify("token");
		clock.now = T0.plusSeconds(59);
		verifier.verify("token");
		verify(firebaseAuth, times(1)).verifyIdToken("token", true);

		clock.now = T0.plusSeconds(60);
		verifier.verify("token");
		verify(firebaseAuth, times(2)).verifyIdToken("token", true);
	}

	@Test
	void refusesATokenRevokedAfterItsLastCheck() throws Exception {
		FirebaseAuthException revoked = mock(FirebaseAuthException.class);
		when(revoked.getAuthErrorCode()).thenReturn(AuthErrorCode.REVOKED_ID_TOKEN);
		FirebaseToken valid = token("uid-1", "qa@teknokent-dev.test");
		when(firebaseAuth.verifyIdToken("token", true)).thenReturn(valid).thenThrow(revoked);

		assertThat(verifier.verify("token")).isPresent();
		clock.now = T0.plusSeconds(61);
		assertThat(verifier.verify("token")).isEmpty();
	}

	@Test
	void neverTrustsATokenPastItsOwnExpiry() throws Exception {
		FirebaseToken expiresSoon = token("uid-1", "qa@teknokent-dev.test");
		when(expiresSoon.getClaims()).thenReturn(Map.of("exp", T0.plusSeconds(10).getEpochSecond()));
		FirebaseAuthException expired = mock(FirebaseAuthException.class);
		when(expired.getAuthErrorCode()).thenReturn(AuthErrorCode.EXPIRED_ID_TOKEN);
		when(firebaseAuth.verifyIdToken("token", true)).thenReturn(expiresSoon).thenThrow(expired);

		assertThat(verifier.verify("token")).isPresent();
		clock.now = T0.plusSeconds(11);
		assertThat(verifier.verify("token")).isEmpty();
	}

	// A Firebase outage says nothing about the token, so it must not read as "invalid token".
	@Test
	void reportsFirebaseUnavailableRatherThanAnInvalidToken() throws Exception {
		for (AuthErrorCode code : new AuthErrorCode[] {null, AuthErrorCode.CERTIFICATE_FETCH_FAILED}) {
			FirebaseAuthException outage = mock(FirebaseAuthException.class);
			when(outage.getAuthErrorCode()).thenReturn(code);
			doThrow(outage).when(firebaseAuth).verifyIdToken("token", true);

			assertThatThrownBy(() -> verifier.verify("token")).isInstanceOf(FirebaseUnavailableException.class);
		}
	}

	@Test
	void refusesBlankTokensAndTokensWithoutAnEmailWithoutTrustingThem() throws Exception {
		assertThat(verifier.verify(" ")).isEmpty();
		verify(firebaseAuth, never()).verifyIdToken(anyString(), anyBoolean());

		FirebaseToken noEmail = token("uid-2", null);
		when(firebaseAuth.verifyIdToken("no-email", true)).thenReturn(noEmail);
		assertThat(verifier.verify("no-email")).isEmpty();
		assertThat(verifier.verify("no-email")).isEmpty();
		verify(firebaseAuth, times(2)).verifyIdToken("no-email", true);
	}

	private static FirebaseToken token(String uid, String email) {
		FirebaseToken token = mock(FirebaseToken.class);
		when(token.getUid()).thenReturn(uid);
		when(token.getEmail()).thenReturn(email);
		when(token.isEmailVerified()).thenReturn(true);
		return token;
	}

	private static final class SetClock extends Clock {
		Instant now = T0;

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
