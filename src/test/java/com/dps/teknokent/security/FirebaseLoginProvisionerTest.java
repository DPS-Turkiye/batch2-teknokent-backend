package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.google.firebase.ErrorCode;
import com.google.firebase.auth.ActionCodeSettings;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.GetUsersResult;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserRecord;

class FirebaseLoginProvisionerTest {

	private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
	private final FirebaseLoginProvisioner provisioner = new FirebaseLoginProvisioner(firebaseAuth);

	@Test
	void createsALoginForAnEmailFirebaseDoesNotKnow() throws Exception {
		FirebaseAuthException notFound = failure(ErrorCode.NOT_FOUND, AuthErrorCode.USER_NOT_FOUND);
		UserRecord created = mock(UserRecord.class);
		when(created.getUid()).thenReturn("uid-1");
		when(firebaseAuth.getUserByEmail("yeni@tekno.com")).thenThrow(notFound);
		when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(created);

		assertThat(provisioner.loginFor("yeni@tekno.com", "Yeni Koordinatör")).isEqualTo("uid-1");
	}

	@Test
	void tellsTheAdminWhyFirebaseRefusedInsteadOfAGenericServerError() throws Exception {
		FirebaseAuthException denied = failure(ErrorCode.PERMISSION_DENIED, null);
		when(firebaseAuth.getUserByEmail("yeni@tekno.com")).thenThrow(denied);

		assertThatThrownBy(() -> provisioner.loginFor("yeni@tekno.com", "Yeni Koordinatör"))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
					assertThat(exception.getReason())
							.isEqualTo("Koordinatörün giriş hesabı Firebase'de oluşturulamadı (PERMISSION_DENIED).");
				});
	}

	@Test
	void seesAPasswordOnlyOnceTheSetupLinkWasUsedAndAsksForEveryoneInOneCall() throws Exception {
		UserInfo password = mock(UserInfo.class);
		when(password.getProviderId()).thenReturn("password");
		UserRecord ready = user("ready", new UserInfo[] {password});
		UserRecord waiting = user("waiting", new UserInfo[0]);
		GetUsersResult result = mock(GetUsersResult.class);
		when(result.getUsers()).thenReturn(Set.of(ready, waiting));
		when(firebaseAuth.getUsers(anyCollection())).thenReturn(result);

		assertThat(provisioner.passwordsSet(List.of("ready", "waiting", "gone")))
				.containsExactlyInAnyOrderEntriesOf(Map.of("ready", true, "waiting", false));
		verify(firebaseAuth, times(1)).getUsers(anyCollection());
	}

	@Test
	void asksAtMostAHundredLoginsPerCallAndLeavesOutABatchFirebaseRefused() throws Exception {
		FirebaseAuthException denied = failure(ErrorCode.PERMISSION_DENIED, null);
		when(firebaseAuth.getUsers(anyCollection())).thenThrow(denied);
		List<String> uids = IntStream.range(0, 150).mapToObj(i -> "uid-" + i).toList();

		assertThat(provisioner.passwordsSet(uids)).isEmpty();
		verify(firebaseAuth, times(2)).getUsers(anyCollection());
		assertThat(provisioner.passwordsSet(List.of())).isEmpty();
		verify(firebaseAuth, times(2)).getUsers(anyCollection());
	}

	@Test
	void revokesTheFirebaseSignInsOfALogin() throws Exception {
		provisioner.revokeSignIns("uid-1");

		verify(firebaseAuth).revokeRefreshTokens("uid-1");
	}

	// A login deleted in Firebase has nothing left to revoke.
	@Test
	void treatsAMissingLoginAsAlreadySignedOut() throws Exception {
		doThrow(failure(ErrorCode.NOT_FOUND, AuthErrorCode.USER_NOT_FOUND))
				.when(firebaseAuth).revokeRefreshTokens("uid-gone");

		provisioner.revokeSignIns("uid-gone");
	}

	@Test
	void reportsAFirebaseFailureAsABadGateway() throws Exception {
		doThrow(failure(ErrorCode.PERMISSION_DENIED, null)).when(firebaseAuth).revokeRefreshTokens("uid-1");

		assertThatThrownBy(() -> provisioner.revokeSignIns("uid-1"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	private static UserRecord user(String uid, UserInfo[] providers) {
		UserRecord user = mock(UserRecord.class);
		when(user.getUid()).thenReturn(uid);
		when(user.getProviderData()).thenReturn(providers);
		return user;
	}

	@Test
	void makesAVerificationLinkThatReturnsToTheSignInPage() throws Exception {
		when(firebaseAuth.generateEmailVerificationLink(eq("mentor@bilkent.edu.tr"), any(ActionCodeSettings.class)))
				.thenReturn("https://verify");

		assertThat(provisioner.emailVerificationLink("mentor@bilkent.edu.tr", "https://menton.com.tr/login"))
				.isEqualTo("https://verify");
	}

	// Firebase refuses a continue URL on a domain it has not authorized; the link then ends on Firebase's own page.
	@Test
	void dropsAContinueUrlFirebaseRefuses() throws Exception {
		FirebaseAuthException unauthorized = failure(ErrorCode.INVALID_ARGUMENT, null);
		when(firebaseAuth.generateEmailVerificationLink(eq("mentor@bilkent.edu.tr"), any(ActionCodeSettings.class)))
				.thenThrow(unauthorized);
		when(firebaseAuth.generateEmailVerificationLink(eq("mentor@bilkent.edu.tr"), isNull()))
				.thenReturn("https://plain");

		assertThat(provisioner.emailVerificationLink("mentor@bilkent.edu.tr", "https://menton.com.tr/login"))
				.isEqualTo("https://plain");
	}

	@Test
	void tellsAMissingLoginApartFromAFirebaseFailureForAReset() throws Exception {
		FirebaseAuthException notFound = failure(ErrorCode.NOT_FOUND, AuthErrorCode.USER_NOT_FOUND);
		FirebaseAuthException denied = failure(ErrorCode.PERMISSION_DENIED, null);
		when(firebaseAuth.generatePasswordResetLink(eq("kimse@tekno.com"), any())).thenThrow(notFound);
		when(firebaseAuth.generatePasswordResetLink(eq("mentor@bilkent.edu.tr"), any())).thenThrow(denied);

		assertThat(provisioner.passwordResetLink("kimse@tekno.com", "https://menton.com.tr/login")).isEmpty();
		verify(firebaseAuth, times(1)).generatePasswordResetLink(eq("kimse@tekno.com"), any());
		assertThatThrownBy(() -> provisioner.passwordResetLink("mentor@bilkent.edu.tr", "https://menton.com.tr/login"))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	// Found in the live test: with e-mail enumeration protection Firebase gives no link instead of an error.
	@Test
	void readsAResetWithoutALinkAsNoLoginAndAVerificationWithoutOneAsUnavailable() throws Exception {
		assertThat(provisioner.passwordResetLink("kimse@tekno.com", "https://menton.com.tr/login")).isEmpty();
		assertThat(provisioner.passwordSetupLink("kimse@tekno.com", "https://menton.com.tr/login")).isEmpty();
		assertThatThrownBy(() -> provisioner.emailVerificationLink("kimse@tekno.com", "https://menton.com.tr/login"))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	private static FirebaseAuthException failure(ErrorCode errorCode, AuthErrorCode authErrorCode) {
		FirebaseAuthException exception = mock(FirebaseAuthException.class);
		when(exception.getErrorCode()).thenReturn(errorCode);
		when(exception.getAuthErrorCode()).thenReturn(authErrorCode);
		return exception;
	}
}
