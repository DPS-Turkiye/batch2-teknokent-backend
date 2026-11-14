package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dps.teknokent.domain.ConsentAnswer;
import com.dps.teknokent.domain.ProcessingConsentPurpose;
import com.dps.teknokent.domain.ProcessingConsentSource;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.DataProcessingConsent;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.DataProcessingConsentRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.FirebaseIdentity;

@ExtendWith(MockitoExtension.class)
class DataProcessingConsentServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
	private static final ConsentAnswer CONSENT = new ConsentAnswer(true, "cv-parsing-2026-11-13");

	@Mock
	private DataProcessingConsentRepository repository;

	private DataProcessingConsentService service;

	@BeforeEach
	void setUp() {
		service = new DataProcessingConsentService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void anAccountHolderIsRecordedAsTheConsenterAndTheSubjectByDefault() {
		UUID userId = UUID.randomUUID();
		signInAs(new AppUserPrincipal(userId, "deniz@example.com", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid-1"));
		when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

		service.record(CONSENT, ProcessingConsentSource.PREVIEW_UPLOAD, null);

		DataProcessingConsent saved = saved();
		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getConsenterUserId()).isEqualTo(userId);
		assertThat(saved.getConsenterFirebaseUid()).isNull();
		assertThat(saved.getSubjectUserId()).isEqualTo(userId);
		assertThat(saved.getPurpose()).isEqualTo(ProcessingConsentPurpose.CV_PARSING);
		assertThat(saved.getSource()).isEqualTo(ProcessingConsentSource.PREVIEW_UPLOAD);
		assertThat(saved.getTextVersion()).isEqualTo("cv-parsing-2026-11-13");
		assertThat(saved.getAcceptedAt()).isEqualTo(NOW);
	}

	@Test
	void aFounderUploadingForATeamMemberIsTheConsenterAndTheMemberTheSubject() {
		UUID founderId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		signInAs(new AppUserPrincipal(founderId, "f@example.com", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid-f"));
		when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

		service.record(CONSENT, ProcessingConsentSource.PROFILE_ANALYSIS, memberId);

		DataProcessingConsent saved = saved();
		assertThat(saved.getConsenterUserId()).isEqualTo(founderId);
		assertThat(saved.getSubjectUserId()).isEqualTo(memberId);
	}

	@Test
	void aPersonStillSigningUpIsRecordedByTheirFirebaseUid() {
		signInAs(new FirebaseIdentity("uid-new", "new@example.com", true));
		when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

		service.record(CONSENT, ProcessingConsentSource.PREVIEW_UPLOAD, null);

		DataProcessingConsent saved = saved();
		assertThat(saved.getConsenterUserId()).isNull();
		assertThat(saved.getConsenterFirebaseUid()).isEqualTo("uid-new");
		assertThat(saved.getSubjectUserId()).isNull();
	}

	@Test
	void theSharedServiceCredentialsCannotGiveConsentForAnybody() {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				"service", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

		assertThatThrownBy(() -> service.record(CONSENT, ProcessingConsentSource.PROFILE_ANALYSIS, UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("signed-in");
		verifyNoInteractions(repository);
	}

	@Test
	void aRefusedOrUnversionedConsentIsNeverRecorded() {
		signInAs(new FirebaseIdentity("uid-new", "new@example.com", true));

		assertThatThrownBy(() -> service.record(
				new ConsentAnswer(false, "cv-parsing-2026-11-13"), ProcessingConsentSource.PREVIEW_UPLOAD, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.record(
				new ConsentAnswer(true, null), ProcessingConsentSource.PREVIEW_UPLOAD, null))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(repository);
	}

	private static void signInAs(Object principal) {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				principal, "firebase", List.of(new SimpleGrantedAuthority("ROLE_MENTEE"))));
	}

	private DataProcessingConsent saved() {
		ArgumentCaptor<DataProcessingConsent> captor = ArgumentCaptor.forClass(DataProcessingConsent.class);
		verify(repository).save(captor.capture());
		return captor.getValue();
	}
}
