package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.security.LoginProvisioner;

class AuthEmailServiceTest {

	private static final String LOGIN = "https://menton.com.tr/login";

	private final LoginProvisioner loginProvisioner = mock(LoginProvisioner.class);
	private final MailService mailService = mock(MailService.class);
	private final AuthEmailService service =
			new AuthEmailService(loginProvisioner, mailService, new RateLimitingConfig());

	@BeforeEach
	void mailIsSetUp() {
		when(mailService.isConfigured()).thenReturn(true);
		when(mailService.link("/login")).thenReturn(LOGIN);
		when(mailService.sendNow(anyString(), anyString(), anyString())).thenReturn(true);
	}

	@Test
	void mailsTheVerificationLinkThroughOurOwnSender() {
		when(loginProvisioner.emailVerificationLink("mentor@bilkent.edu.tr", LOGIN)).thenReturn("https://verify");

		service.sendVerification(" Mentor@Bilkent.edu.tr ");

		verify(mailService).sendNow(
				eq("mentor@bilkent.edu.tr"), eq("menton e-posta adresinizi doğrulayın"), contains("https://verify"));
	}

	@Test
	void mailsAResetLinkToAnAddressWithALogin() {
		when(loginProvisioner.passwordResetLink("mentor@metu.edu.tr", LOGIN)).thenReturn(Optional.of("https://reset"));

		service.sendPasswordReset("mentor@metu.edu.tr");

		verify(mailService).sendNow(
				eq("mentor@metu.edu.tr"), eq("menton şifrenizi sıfırlayın"), contains("https://reset"));
	}

	@Test
	void answersAResetForAnUnknownAddressWithoutMailingAnyone() {
		when(loginProvisioner.passwordResetLink("kimse@tekno.com", LOGIN)).thenReturn(Optional.empty());

		service.sendPasswordReset("kimse@tekno.com");

		verify(mailService, never()).sendNow(anyString(), anyString(), anyString());
	}

	@Test
	void mailsAnAddressAtMostThreeResetsPerWindowAndQuietlySkipsTheRest() {
		when(loginProvisioner.passwordResetLink("mentor@metu.edu.tr", LOGIN)).thenReturn(Optional.of("https://reset"));

		for (int attempt = 0; attempt < 5; attempt++) {
			service.sendPasswordReset("mentor@metu.edu.tr");
		}

		verify(mailService, times(3)).sendNow(eq("mentor@metu.edu.tr"), anyString(), anyString());
	}

	@Test
	void refusesAFourthVerificationMailInAWindow() {
		when(loginProvisioner.emailVerificationLink("mentor@yahoo.com", LOGIN)).thenReturn("https://verify");
		for (int attempt = 0; attempt < 3; attempt++) {
			service.sendVerification("mentor@yahoo.com");
		}

		assertThatThrownBy(() -> service.sendVerification("mentor@yahoo.com"))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
	}

	// The frontend falls back to Firebase's own e-mail on a 503.
	@Test
	void answers503WhenMailIsOffOrSesRefuses() {
		when(mailService.isConfigured()).thenReturn(false);
		assertThatThrownBy(() -> service.sendVerification("mentor@yahoo.com"))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

		when(mailService.isConfigured()).thenReturn(true);
		when(loginProvisioner.passwordResetLink("mentor@metu.edu.tr", LOGIN)).thenReturn(Optional.of("https://reset"));
		when(mailService.sendNow(anyString(), anyString(), anyString())).thenReturn(false);
		assertThatThrownBy(() -> service.sendPasswordReset("mentor@metu.edu.tr"))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}
}
