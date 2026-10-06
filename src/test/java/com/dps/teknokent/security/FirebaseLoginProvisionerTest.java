package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
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

	private static FirebaseAuthException failure(ErrorCode errorCode, AuthErrorCode authErrorCode) {
		FirebaseAuthException exception = mock(FirebaseAuthException.class);
		when(exception.getErrorCode()).thenReturn(errorCode);
		when(exception.getAuthErrorCode()).thenReturn(authErrorCode);
		return exception;
	}
}
