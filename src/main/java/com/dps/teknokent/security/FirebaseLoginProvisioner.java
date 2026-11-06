package com.dps.teknokent.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.Texts;
import com.google.firebase.auth.ActionCodeSettings;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UidIdentifier;
import com.google.firebase.auth.UserIdentifier;
import com.google.firebase.auth.UserRecord;

import lombok.extern.slf4j.Slf4j;

/**
 * The login is created without a password; its owner sets one through "Şifremi unuttum", so no password ever
 * passes through the admin or this backend.
 */
@Slf4j
public class FirebaseLoginProvisioner implements LoginProvisioner {

	private static final String PASSWORD_PROVIDER = "password";
	private static final int MAX_USERS_PER_LOOKUP = 100;

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
				throw loginUnavailable(email, exception);
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
			throw loginUnavailable(email, exception);
		}
	}

	/**
	 * A Firebase failure here is a server-side setup problem (credentials, permissions), not the admin's input.
	 * The reason is sent back in the admin's language with Firebase's own code, so the admin's toast says what to fix
	 * instead of the generic server-error text.
	 */
	private static ResponseStatusException loginUnavailable(String email, FirebaseAuthException exception) {
		log.error("Could not get a Firebase login for {}: {} {}", email, exception.getErrorCode(), exception.getMessage());
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
				Texts.forRequest("error.coordinatorLoginFailed", exception.getErrorCode()), exception);
	}

	/**
	 * The same reset link Firebase's own "Şifremi unuttum" email carries.
	 */
	@Override
	public Optional<String> passwordSetupLink(String email, String continueUrl) {
		try {
			return Optional.of(link(firebaseAuth::generatePasswordResetLink, email, continueUrl));
		} catch (FirebaseAuthException exception) {
			log.warn("No password setup link for {}: {}", email, exception.getMessage());
			return Optional.empty();
		}
	}

	@Override
	public Optional<String> passwordResetLink(String email, String continueUrl) {
		try {
			return Optional.of(link(firebaseAuth::generatePasswordResetLink, email, continueUrl));
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
				return Optional.empty();
			}
			throw linkUnavailable(email, exception);
		}
	}

	@Override
	public String emailVerificationLink(String email, String continueUrl) {
		try {
			return link(firebaseAuth::generateEmailVerificationLink, email, continueUrl);
		} catch (FirebaseAuthException exception) {
			throw linkUnavailable(email, exception);
		}
	}

	/**
	 * The continue URL has to be an authorized domain in Firebase; if it is not, the link still works and simply
	 * ends on Firebase's own page. A missing login is the same answer either way, so it is not asked twice.
	 */
	private static String link(LinkRequest request, String email, String continueUrl) throws FirebaseAuthException {
		try {
			return request.generate(email, ActionCodeSettings.builder().setUrl(continueUrl).build());
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
				throw exception;
			}
			return request.generate(email, null);
		} catch (IllegalArgumentException invalidContinueUrl) {
			return request.generate(email, null);
		}
	}

	private static ResponseStatusException linkUnavailable(String email, FirebaseAuthException exception) {
		log.warn("No Firebase e-mail link for {}: {} {}", email, exception.getErrorCode(), exception.getMessage());
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase could not make the e-mail link.",
				exception);
	}

	@FunctionalInterface
	private interface LinkRequest {
		String generate(String email, ActionCodeSettings settings) throws FirebaseAuthException;
	}

	/**
	 * A login made by {@link #loginFor} has no "password" provider until its setup link is used. One Firebase
	 * call per {@value #MAX_USERS_PER_LOOKUP} logins, not one per login.
	 */
	@Override
	public Map<String, Boolean> passwordsSet(Collection<String> uids) {
		List<UserIdentifier> identifiers = uids.stream().distinct().<UserIdentifier>map(UidIdentifier::new).toList();
		Map<String, Boolean> answers = new HashMap<>();
		for (int from = 0; from < identifiers.size(); from += MAX_USERS_PER_LOOKUP) {
			List<UserIdentifier> batch = identifiers.subList(from, Math.min(from + MAX_USERS_PER_LOOKUP, identifiers.size()));
			try {
				for (UserRecord user : firebaseAuth.getUsers(batch).getUsers()) {
					answers.put(user.getUid(), Arrays.stream(user.getProviderData())
							.anyMatch(provider -> PASSWORD_PROVIDER.equals(provider.getProviderId())));
				}
			} catch (FirebaseAuthException exception) {
				log.warn("Could not read {} Firebase logins: {}", batch.size(), exception.getMessage());
			}
		}
		return answers;
	}

	@Override
	public void revokeSignIns(String uid) {
		try {
			firebaseAuth.revokeRefreshTokens(uid);
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
				return;
			}
			log.error("Could not revoke the Firebase sign-ins of {}: {} {}", uid, exception.getErrorCode(),
					exception.getMessage());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					Texts.forRequest("error.signInRevokeFailed", exception.getErrorCode()), exception);
		}
	}
}
