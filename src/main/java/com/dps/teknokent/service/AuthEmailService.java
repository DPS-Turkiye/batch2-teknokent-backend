package com.dps.teknokent.service;

import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.repository.AppUserIdentityRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sign-up verification and "Şifremi unuttum" e-mails. University and Yahoo filters drop Firebase's own sender, so the
 * link Firebase would mail goes out through SES from our domain instead. A 503 tells the frontend to fall back to
 * Firebase's e-mail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthEmailService {

	// Per address, so "Şifremi unuttum" cannot be used to fill a stranger's inbox.
	static final long MAILS_PER_WINDOW = 3;
	static final long WINDOW_MS = 15 * 60_000;

	private final LoginProvisioner loginProvisioner;
	private final MailService mailService;
	private final RateLimitingConfig rateLimitingConfig;
	private final AppUserRepository appUserRepository;
	private final InstitutionPersonRepository institutionPersonRepository;
	private final AppUserIdentityRepository identityRepository;

	public void sendVerification(String email) {
		String address = requireMail(email);
		if (!withinLimit("verify_mail:", address)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many e-mails; try again later.");
		}
		send(address, "mail.verifyEmail", loginProvisioner.emailVerificationLink(address, mailService.link("/login")));
	}

	/**
	 * Answers the same whether or not the address has a login, so the form cannot be used to find accounts. An
	 * account that is waiting for its first login gets one here, with a link to set its password.
	 */
	public void sendPasswordReset(String email) {
		String address = requireMail(email);
		if (!withinLimit("reset_mail:", address)) {
			return;
		}
		String login = mailService.link("/login");
		Optional<String> reset = loginProvisioner.passwordResetLink(address, login);
		if (reset.isPresent()) {
			send(address, "mail.passwordReset", reset.get());
			return;
		}
		accountWaitingForLogin(address).ifPresent(account -> sendLoginSetup(account, address, login));
	}

	/**
	 * A founder adds team members by e-mail as approved accounts with no Firebase login, and the bearer filter links
	 * such an account to a verified login with its address on first sign-in. Only accounts that path may take over
	 * qualify: never an admin or coordinator, whose logins an admin creates, nor an account tied to an institution
	 * sign-in or roster record, which is bound to that identity instead. Pending and rejected accounts wait for a
	 * decision first, and a deleted account is never approved.
	 */
	private Optional<AppUser> accountWaitingForLogin(String address) {
		return appUserRepository.findByEmailIgnoreCase(address)
				.filter(user -> user.getStatus() == UserAccountStatus.APPROVED)
				.filter(user -> !isStaff(user))
				.filter(user -> !institutionPersonRepository.existsByUserId(user.getId())
						&& !identityRepository.existsByUserId(user.getId()));
	}

	// The primary role is checked next to the grants, so a lapsed grant row never makes a coordinator claimable.
	private static boolean isStaff(AppUser user) {
		return user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.COORDINATOR
				|| user.has(UserRole.ADMIN) || user.has(UserRole.COORDINATOR);
	}

	/**
	 * The login is born verified because its setup link goes only to this address, and the bearer filter claims an
	 * account by e-mail for a verified login only. A Firebase failure answers like an unknown address: Firebase
	 * creates a login whole or not at all, so nothing is left that blocks a later sign-up or reset. A mail SES refuses
	 * is a 503, as for an existing login: the frontend then sends Firebase's own e-mail, which now finds the login.
	 */
	private void sendLoginSetup(AppUser account, String address, String login) {
		try {
			loginProvisioner.verifiedLoginFor(address, account.getFullName());
		} catch (RuntimeException failure) {
			// The provisioner logs Firebase's answer; this public endpoint logs no address.
			log.warn("Could not create a login for account {} on a password reset: {}", account.getId(),
					failure.getClass().getSimpleName());
			return;
		}
		loginProvisioner.passwordSetupLink(address, login).ifPresentOrElse(
				link -> send(address, "mail.accountSetup", account.getFullName(), link, login),
				() -> log.warn("No setup link for the new login of account {}; its next reset sends one",
						account.getId()));
	}

	private String requireMail(String email) {
		if (!mailService.isConfigured()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "E-mail is not configured.");
		}
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private boolean withinLimit(String kind, String address) {
		return rateLimitingConfig.resolveBucket(kind + address, MAILS_PER_WINDOW, WINDOW_MS).tryConsume(1);
	}

	private void send(String address, String key, Object... args) {
		if (!mailService.sendNow(address, Texts.forRequest(key + ".subject"), Texts.forRequest(key + ".body", args))) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The e-mail could not be sent.");
		}
	}
}
