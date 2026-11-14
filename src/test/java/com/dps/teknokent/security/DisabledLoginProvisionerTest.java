package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.exception.InvalidRequestException;

/** What the application does when it runs without Firebase credentials. */
class DisabledLoginProvisionerTest {

	private final LoginProvisioner provisioner = new DisabledLoginProvisioner();

	@Test
	void anAdminCannotCreateALoginAndIsToldWhy() {
		assertThatThrownBy(() -> provisioner.loginFor("yeni@tekno.com", "Yeni Koordinatör"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Firebase is not configured");
	}

	// A 503 is what makes the frontend fall back to Firebase's own e-mail; an empty answer would read as "no login".
	@Test
	void linksAndSessionsThatNeedFirebaseAreUnavailableRatherThanSilentlyMissing() {
		assertUnavailable(() -> provisioner.passwordResetLink("ayse@tekno.com", "https://menton.com.tr/login"));
		assertUnavailable(() -> provisioner.emailVerificationLink("ayse@tekno.com", "https://menton.com.tr/login"));
		assertUnavailable(() -> provisioner.customToken("odtu_e100001", "e100001@metu.edu.tr", "Deniz", true));
	}

	// The invitation mail still goes out, only without the link.
	@Test
	void anInvitationSimplyCarriesNoSetupLink() {
		assertThat(provisioner.passwordSetupLink("yeni@tekno.com", "https://menton.com.tr/login")).isEmpty();
	}

	@Test
	void nobodyHasALoginSoThereIsNothingToFindRevokeOrDelete() {
		assertThat(provisioner.passwordsSet(List.of("uid-1", "uid-2"))).isEmpty();
		assertThat(provisioner.uidForEmail("ayse@tekno.com")).isEmpty();
		assertThatCode(() -> provisioner.deleteLogin("uid-1")).doesNotThrowAnyException();
		assertThatCode(() -> provisioner.revokeSignIns("ayse@tekno.com")).doesNotThrowAnyException();
	}

	private static void assertUnavailable(ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
				exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}
}
