package com.dps.teknokent.security;

import java.util.Optional;

import com.dps.teknokent.exception.InvalidRequestException;
import com.google.firebase.auth.ActionCodeSettings;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;

import lombok.extern.slf4j.Slf4j;

/**
 * The login is created without a password; its owner sets one through "Şifremi unuttum", so no password ever
 * passes through the admin or this backend.
 */
@Slf4j
public class FirebaseLoginProvisioner implements LoginProvisioner {

	private final FirebaseAuth firebaseAuth;

	public FirebaseLoginProvisioner(FirebaseAuth firebaseAuth) {
		this.firebaseAuth = firebaseAuth;
	}

	@Override
	public String loginFor(String email, String displayName) {
		try {
			return firebaseAuth.getUserByEmail(email).getUid();
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
				throw new IllegalStateException("Could not look up the Firebase login for " + email, exception);
			}
		}
		try {
			return firebaseAuth.createUser(new UserRecord.CreateRequest()
					.setEmail(email)
					.setDisplayName(displayName)
					.setEmailVerified(false))
					.getUid();
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
				throw new InvalidRequestException("A login for this email was created at the same time; try again.");
			}
			throw new IllegalStateException("Could not create a Firebase login for " + email, exception);
		}
	}

	/**
	 * The same reset link Firebase's own "Şifremi unuttum" email carries. The continue URL has to be an authorized
	 * domain in Firebase; if it is not, the link still works and simply ends on Firebase's own page.
	 */
	@Override
	public Optional<String> passwordSetupLink(String email, String continueUrl) {
		try {
			return Optional.of(firebaseAuth.generatePasswordResetLink(
					email,
					ActionCodeSettings.builder().setUrl(continueUrl).build()));
		} catch (FirebaseAuthException | IllegalArgumentException withContinueUrl) {
			try {
				return Optional.of(firebaseAuth.generatePasswordResetLink(email));
			} catch (FirebaseAuthException exception) {
				log.warn("No password setup link for {}: {}", email, exception.getMessage());
				return Optional.empty();
			}
		}
	}
}
