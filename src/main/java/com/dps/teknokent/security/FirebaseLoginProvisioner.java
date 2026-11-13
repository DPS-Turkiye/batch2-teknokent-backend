package com.dps.teknokent.security;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.exception.ConflictException;
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

	private static final SecureRandom RANDOM = new SecureRandom();

	public FirebaseLoginProvisioner(FirebaseAuth firebaseAuth) {
		this.firebaseAuth = firebaseAuth;
	}

	@Override
	public String loginFor(String email, String displayName) {
		try {
			UserRecord existing = firebaseAuth.getUserByEmail(email);
			lockOutIfUnverified(existing);
			return existing.getUid();
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
	 * Anyone can create an unverified Firebase login for any address, so a login that never proved the address may
	 * belong to someone other than the person being made a coordinator. Its password is replaced with a random one
	 * and its sessions are ended; the real owner sets a password through the setup link we mail.
	 */
	private void lockOutIfUnverified(UserRecord login) throws FirebaseAuthException {
		if (login.isEmailVerified()) {
			return;
		}
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		firebaseAuth.updateUser(new UserRecord.UpdateRequest(login.getUid())
				.setPassword(Base64.getUrlEncoder().withoutPadding().encodeToString(secret)));
		firebaseAuth.revokeRefreshTokens(login.getUid());
		log.warn("Locked out an unverified Firebase login before linking it to a coordinator: {}", login.getUid());
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
			return Optional.ofNullable(link(firebaseAuth::generatePasswordResetLink, email, continueUrl));
		} catch (FirebaseAuthException exception) {
			log.warn("No password setup link for {}: {}", email, exception.getMessage());
			return Optional.empty();
		}
	}

	/** With e-mail enumeration protection on, Firebase answers an unknown address with no link instead of an error. */
	@Override
	public Optional<String> passwordResetLink(String email, String continueUrl) {
		try {
			return Optional.ofNullable(link(firebaseAuth::generatePasswordResetLink, email, continueUrl));
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
				return Optional.empty();
			}
			throw linkUnavailable(email, exception);
		}
	}

	@Override
	public String emailVerificationLink(String email, String continueUrl) {
		String link;
		try {
			link = link(firebaseAuth::generateEmailVerificationLink, email, continueUrl);
		} catch (FirebaseAuthException exception) {
			throw linkUnavailable(email, exception);
		}
		if (link == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase returned no verification link.");
		}
		return link;
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

	/** Signed locally with the service account's private key, so no extra Google permission is needed. */
	@Override
	public String customToken(String uid, String email, String displayName, boolean emailVerified) {
		ensureLogin(uid, email, displayName, emailVerified);
		try {
			return firebaseAuth.createCustomToken(uid);
		} catch (FirebaseAuthException exception) {
			throw sessionUnavailable(exception);
		}
	}

	private void ensureLogin(String uid, String email, String displayName, boolean emailVerified) {
		try {
			firebaseAuth.getUser(uid);
			return;
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
				throw sessionUnavailable(exception);
			}
		}
		UserRecord.CreateRequest request = new UserRecord.CreateRequest()
				.setUid(uid)
				.setEmail(email)
				.setEmailVerified(emailVerified);
		// Firebase refuses a blank display name; OTG may send no name at all.
		if (displayName != null && !displayName.isBlank()) {
			request.setDisplayName(displayName);
		}
		try {
			firebaseAuth.createUser(request);
		} catch (FirebaseAuthException exception) {
			// Another Firebase login already holds the address while no Menton account does.
			if (exception.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
				throw new ConflictException(Texts.forRequest("error.institutionEmailTaken"));
			}
			throw sessionUnavailable(exception);
		}
	}

	private static ResponseStatusException sessionUnavailable(FirebaseAuthException exception) {
		log.error("Could not prepare a Firebase login: {} {}", exception.getErrorCode(), exception.getMessage());
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase could not start the session.",
				exception);
	}

	@Override
	public Optional<String> uidForEmail(String email) {
		try {
			return Optional.of(firebaseAuth.getUserByEmail(email).getUid());
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
				return Optional.empty();
			}
			log.error("Could not look up a Firebase login before deleting its account: {} {}",
					exception.getErrorCode(), exception.getMessage());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					Texts.forRequest("error.userDeleteLoginLookupFailed", exception.getErrorCode()), exception);
		}
	}

	@Override
	public void deleteLogin(String uid) {
		try {
			firebaseAuth.revokeRefreshTokens(uid);
			firebaseAuth.deleteUser(uid);
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
				return;
			}
			throw new IllegalStateException("Firebase could not delete the login: " + exception.getErrorCode(),
					exception);
		}
	}

	@Override
	public void revokeSignIns(String email) {
		try {
			String uid = firebaseAuth.getUserByEmail(email).getUid();
			firebaseAuth.revokeRefreshTokens(uid);
		} catch (FirebaseAuthException exception) {
			if (exception.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
				log.warn("No Firebase login for {}; only app sessions are ended", email);
				return;
			}
			log.error("Could not revoke the Firebase sign-ins of {}: {} {}", email, exception.getErrorCode(),
					exception.getMessage());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					Texts.forRequest("error.signInRevokeFailed", exception.getErrorCode()), exception);
		}
	}
}
