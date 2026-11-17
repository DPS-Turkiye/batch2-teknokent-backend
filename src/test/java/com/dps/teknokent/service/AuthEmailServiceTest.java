package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserIdentityRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.security.LoginProvisioner;

class AuthEmailServiceTest {

	private static final String LOGIN = "https://menton.com.tr/login";

	private final LoginProvisioner loginProvisioner = mock(LoginProvisioner.class);
	private final MailService mailService = mock(MailService.class);
	private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
	private final InstitutionPersonRepository institutionPersonRepository = mock(InstitutionPersonRepository.class);
	private final AppUserIdentityRepository identityRepository = mock(AppUserIdentityRepository.class);
	private final AuthEmailService service = new AuthEmailService(loginProvisioner, mailService,
			new RateLimitingConfig(), appUserRepository, institutionPersonRepository, identityRepository);

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
		verify(loginProvisioner, never()).verifiedLoginFor(anyString(), any());
		verify(loginProvisioner, never()).passwordSetupLink(anyString(), anyString());
		verifyNoInteractions(appUserRepository);
	}

	@Test
	void answersAResetForAnUnknownAddressWithoutMailingAnyone() {
		when(loginProvisioner.passwordResetLink("kimse@tekno.com", LOGIN)).thenReturn(Optional.empty());

		service.sendPasswordReset("kimse@tekno.com");

		verify(mailService, never()).sendNow(anyString(), anyString(), anyString());
		verify(loginProvisioner, never()).verifiedLoginFor(anyString(), any());
	}

	@Test
	void mailsAnAddressAtMostThreeResetsPerWindowAndQuietlySkipsTheRest() {
		when(loginProvisioner.passwordResetLink("mentor@metu.edu.tr", LOGIN)).thenReturn(Optional.of("https://reset"));

		for (int attempt = 0; attempt < 5; attempt++) {
			service.sendPasswordReset("mentor@metu.edu.tr");
		}

		verify(mailService, times(3)).sendNow(eq("mentor@metu.edu.tr"), anyString(), anyString());
	}

	// A founder adds team members by e-mail; their accounts have no Firebase login until one is made here.
	@Test
	void givesAnApprovedAccountWithoutALoginAVerifiedLoginAndMailsItsSetupLink() {
		AppUser member = waitingMember(UserAccountStatus.APPROVED);
		when(loginProvisioner.passwordSetupLink("uye@tekno.com", LOGIN)).thenReturn(Optional.of("https://setup"));

		service.sendPasswordReset(" Uye@Tekno.com ");

		verify(loginProvisioner).verifiedLoginFor("uye@tekno.com", member.getFullName());
		verify(mailService).sendNow(eq("uye@tekno.com"), eq("menton hesabınız için şifre belirleyin"),
				argThat(body -> body.contains("https://setup") && body.contains(LOGIN)
						&& body.contains("Üye Kişi")));
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
	void leavesAnAccountThatIsNotApprovedWithoutALogin(UserAccountStatus status) {
		waitingMember(status);

		service.sendPasswordReset("uye@tekno.com");

		assertNothingCreatedOrSent();
	}

	@ParameterizedTest
	@EnumSource(value = UserRole.class, names = { "ADMIN", "COORDINATOR" })
	void neverMakesALoginForAnAdminOrCoordinatorByPrimaryRole(UserRole role) {
		waitingMember(UserAccountStatus.APPROVED).setRole(role);

		service.sendPasswordReset("uye@tekno.com");

		assertNothingCreatedOrSent();
	}

	@ParameterizedTest
	@EnumSource(value = UserRole.class, names = { "ADMIN", "COORDINATOR" })
	void neverMakesALoginForAnAccountGrantedAdminOrCoordinator(UserRole role) {
		waitingMember(UserAccountStatus.APPROVED).grant(role, PermissionSource.ADMIN, Instant.now());

		service.sendPasswordReset("uye@tekno.com");

		assertNothingCreatedOrSent();
	}

	@Test
	void neverMakesALoginForAnAccountOnAnInstitutionRoster() {
		AppUser member = waitingMember(UserAccountStatus.APPROVED);
		when(institutionPersonRepository.existsByUserId(member.getId())).thenReturn(true);

		service.sendPasswordReset("uye@tekno.com");

		assertNothingCreatedOrSent();
	}

	// OTG included: such an account is reached through its institution sign-in, never claimed by address.
	@Test
	void neverMakesALoginForAnAccountWithAnInstitutionSignIn() {
		AppUser member = waitingMember(UserAccountStatus.APPROVED);
		when(identityRepository.existsByUserId(member.getId())).thenReturn(true);

		service.sendPasswordReset("uye@tekno.com");

		assertNothingCreatedOrSent();
	}

	@Test
	void countsAMadeLoginAgainstTheSameLimitBeforeCreatingAnything() {
		waitingMember(UserAccountStatus.APPROVED);
		when(loginProvisioner.passwordSetupLink("uye@tekno.com", LOGIN)).thenReturn(Optional.of("https://setup"));

		for (int attempt = 0; attempt < 5; attempt++) {
			service.sendPasswordReset("uye@tekno.com");
		}

		verify(loginProvisioner, times(3)).passwordResetLink("uye@tekno.com", LOGIN);
		verify(loginProvisioner, times(3)).verifiedLoginFor(eq("uye@tekno.com"), any());
		verify(mailService, times(3)).sendNow(eq("uye@tekno.com"), anyString(), anyString());
	}

	// A 502 when Firebase fails; a 400 when a login appeared meanwhile or Firebase is not configured.
	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void answersAFirebaseFailureLikeAnUnknownAddress(boolean firebaseDown) {
		waitingMember(UserAccountStatus.APPROVED);
		RuntimeException failure = firebaseDown
				? new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Firebase is down")
				: new InvalidRequestException("A login for this email was created at the same time; try again.");
		when(loginProvisioner.verifiedLoginFor(eq("uye@tekno.com"), any())).thenThrow(failure);

		service.sendPasswordReset("uye@tekno.com");

		verify(loginProvisioner, never()).passwordSetupLink(anyString(), anyString());
		verify(mailService, never()).sendNow(anyString(), anyString(), anyString());
	}

	@Test
	void sendsNothingWhenFirebaseGivesNoSetupLinkForTheNewLogin() {
		waitingMember(UserAccountStatus.APPROVED);
		when(loginProvisioner.passwordSetupLink("uye@tekno.com", LOGIN)).thenReturn(Optional.empty());

		service.sendPasswordReset("uye@tekno.com");

		verify(mailService, never()).sendNow(anyString(), anyString(), anyString());
	}

	// The frontend then falls back to Firebase's own e-mail, which now finds the login.
	@Test
	void answers503WhenSesRefusesTheSetupMail() {
		waitingMember(UserAccountStatus.APPROVED);
		when(loginProvisioner.passwordSetupLink("uye@tekno.com", LOGIN)).thenReturn(Optional.of("https://setup"));
		when(mailService.sendNow(anyString(), anyString(), anyString())).thenReturn(false);

		assertThatThrownBy(() -> service.sendPasswordReset("uye@tekno.com"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	private AppUser waitingMember(UserAccountStatus status) {
		AppUser member = new AppUser();
		member.setId(UUID.randomUUID());
		member.setFullName("Üye Kişi");
		member.setEmail("Uye@Tekno.com");
		member.setRole(UserRole.MENTEE);
		member.setStatus(status);
		when(loginProvisioner.passwordResetLink("uye@tekno.com", LOGIN)).thenReturn(Optional.empty());
		when(appUserRepository.findByEmailIgnoreCase("uye@tekno.com")).thenReturn(Optional.of(member));
		return member;
	}

	private void assertNothingCreatedOrSent() {
		verify(loginProvisioner, never()).verifiedLoginFor(anyString(), any());
		verify(loginProvisioner, never()).passwordSetupLink(anyString(), anyString());
		verify(mailService, never()).sendNow(anyString(), anyString(), anyString());
	}

	@Test
	void refusesAFourthVerificationMailInAWindow() {
		when(loginProvisioner.emailVerificationLink("mentor@yahoo.com", LOGIN)).thenReturn("https://verify");
		for (int attempt = 0; attempt < 3; attempt++) {
			service.sendVerification("mentor@yahoo.com");
		}

		assertThatThrownBy(() -> service.sendVerification("mentor@yahoo.com"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
	}

	// The frontend falls back to Firebase's own e-mail on a 503.
	@Test
	void answers503WhenMailIsOffOrSesRefuses() {
		when(mailService.isConfigured()).thenReturn(false);
		assertThatThrownBy(() -> service.sendVerification("mentor@yahoo.com"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

		when(mailService.isConfigured()).thenReturn(true);
		when(loginProvisioner.passwordResetLink("mentor@metu.edu.tr", LOGIN)).thenReturn(Optional.of("https://reset"));
		when(mailService.sendNow(anyString(), anyString(), anyString())).thenReturn(false);
		assertThatThrownBy(() -> service.sendPasswordReset("mentor@metu.edu.tr"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}
}
