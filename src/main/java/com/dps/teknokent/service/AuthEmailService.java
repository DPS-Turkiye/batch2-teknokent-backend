package com.dps.teknokent.service;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Sign-up verification and "Şifremi unuttum" e-mails. University and Yahoo filters drop Firebase's own sender, so the
 * link Firebase would mail goes out through SES from our domain instead. A 503 tells the frontend to fall back to
 * Firebase's e-mail.
 */
@Service
@RequiredArgsConstructor
public class AuthEmailService {

	// Per address, so "Şifremi unuttum" cannot be used to fill a stranger's inbox.
	static final long MAILS_PER_WINDOW = 3;
	static final long WINDOW_MS = 15 * 60_000;

	private final LoginProvisioner loginProvisioner;
	private final MailService mailService;
	private final RateLimitingConfig rateLimitingConfig;

	public void sendVerification(String email) {
		String address = requireMail(email);
		if (!withinLimit("verify_mail:", address)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many e-mails; try again later.");
		}
		send(address, "mail.verifyEmail", loginProvisioner.emailVerificationLink(address, mailService.link("/login")));
	}

	/** Answers the same whether or not the address has a login, so the form cannot be used to find accounts. */
	public void sendPasswordReset(String email) {
		String address = requireMail(email);
		if (!withinLimit("reset_mail:", address)) {
			return;
		}
		loginProvisioner.passwordResetLink(address, mailService.link("/login"))
				.ifPresent(link -> send(address, "mail.passwordReset", link));
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

	private void send(String address, String key, String link) {
		if (!mailService.sendNow(address, Texts.forRequest(key + ".subject"), Texts.forRequest(key + ".body", link))) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The e-mail could not be sent.");
		}
	}
}
