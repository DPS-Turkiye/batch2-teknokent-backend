package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.UserDeletionResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserIdentityRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.security.institution.OtgLoginProvider;

/**
 * What the deletion does around its two transactions; what is erased is in {@code UserDeletionIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class UserDeletionServiceTest {

	private static final String EMAIL = "deniz@dongu.co";

	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private AppUserIdentityRepository identityRepository;
	@Mock
	private InstitutionPersonRepository institutionPersonRepository;
	@Mock
	private UserDataEraser eraser;
	@Mock
	private LoginProvisioner loginProvisioner;
	@Mock
	private UserDeletionCleanupService cleanupService;
	@Mock
	private PlatformTransactionManager transactionManager;

	private final UUID auditId = UUID.randomUUID();
	private UserDeletionService service;
	private AppUser admin;
	private AppUser target;

	@BeforeEach
	void setUp() {
		service = new UserDeletionService(appUserRepository, identityRepository, institutionPersonRepository, eraser,
				loginProvisioner, cleanupService, transactionManager);
		admin = user(UserRole.ADMIN, "admin@menton.test", "uid-admin");
		target = user(UserRole.MENTEE, EMAIL, "uid-stored");
		when(appUserRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
		when(institutionPersonRepository.existsByUserId(target.getId())).thenReturn(false);
		when(identityRepository.existsByUserIdAndProviderNot(target.getId(), OtgLoginProvider.ID)).thenReturn(false);
	}

	// The stored uid goes stale when a login is deleted and made again, so the address is asked about as well.
	@Test
	void removesBothTheLoginTheAccountStoredAndTheOneItsAddressHasNow() {
		when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(target));
		when(loginProvisioner.uidForEmail(EMAIL)).thenReturn(Optional.of("uid-by-email"));
		when(appUserRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
		when(eraser.erase(same(target), same(admin), eq(Set.of("uid-stored", "uid-by-email")), any(Instant.class)))
				.thenReturn(auditId);
		when(cleanupService.runFor(auditId)).thenReturn(false);

		UserDeletionResponse response = service.delete(admin.getId(), target.getId(), EMAIL);

		assertThat(response).isEqualTo(new UserDeletionResponse(target.getId(), false));
	}

	@Test
	void anAccountThatNeverHadALoginIsErasedWithNoLoginToRemove() {
		target.setFirebaseUid(" ");
		when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(target));
		when(loginProvisioner.uidForEmail(EMAIL)).thenReturn(Optional.empty());
		when(appUserRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
		when(eraser.erase(same(target), same(admin), eq(Set.of()), any(Instant.class))).thenReturn(auditId);
		when(cleanupService.runFor(auditId)).thenReturn(false);

		assertThat(service.delete(admin.getId(), target.getId(), EMAIL).cleanupPending()).isFalse();
	}

	// Firebase is asked outside the transaction, so the account is read again once it is locked.
	@Test
	void aLoginTheAccountGotWhileFirebaseWasBeingAskedIsRemovedToo() {
		AppUser before = user(UserRole.MENTEE, EMAIL, null);
		before.setId(target.getId());
		target.setFirebaseUid("uid-linked-meanwhile");
		when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(before))
				.thenReturn(Optional.of(target));
		when(loginProvisioner.uidForEmail(EMAIL)).thenReturn(Optional.empty());
		when(appUserRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
		when(eraser.erase(same(target), same(admin), eq(Set.of("uid-linked-meanwhile")), any(Instant.class)))
				.thenReturn(auditId);
		when(cleanupService.runFor(auditId)).thenReturn(true);

		assertThat(service.delete(admin.getId(), target.getId(), EMAIL).cleanupPending()).isTrue();
	}

	@Test
	void anAccountMadeAnAdminWhileFirebaseWasBeingAskedIsNotErased() {
		AppUser promoted = user(UserRole.ADMIN, EMAIL, "uid-stored");
		promoted.setId(target.getId());
		when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(target))
				.thenReturn(Optional.of(promoted));
		when(loginProvisioner.uidForEmail(EMAIL)).thenReturn(Optional.empty());
		when(appUserRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(promoted));

		assertThatThrownBy(() -> service.delete(admin.getId(), target.getId(), EMAIL))
				.isInstanceOfSatisfying(InvalidRequestException.class,
						error -> assertThat(error.code()).isEqualTo(UserDeletionService.CODE_ADMIN));
		verify(eraser, never()).erase(any(), any(), any(), any());
		verify(cleanupService, never()).runFor(any());
	}

	@Test
	void anAccountGoneBeforeItCouldBeLockedIsNotFoundAndNothingIsErased() {
		when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(target));
		when(loginProvisioner.uidForEmail(EMAIL)).thenReturn(Optional.empty());
		when(appUserRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.delete(admin.getId(), target.getId(), EMAIL))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(eraser, never()).erase(any(), any(), any(), any());
		verify(cleanupService, never()).runFor(any());
	}

	// The account is already erased and committed: a crash in the cleanup must not turn that into an error.
	@Test
	void aCleanupThatBreaksAfterTheCommitIsLeftToTheSchedulerAndTheDeletionStillAnswers() {
		when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(target));
		when(loginProvisioner.uidForEmail(EMAIL)).thenReturn(Optional.empty());
		when(appUserRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
		when(eraser.erase(same(target), same(admin), eq(Set.of("uid-stored")), any(Instant.class)))
				.thenReturn(auditId);
		when(cleanupService.runFor(auditId)).thenThrow(new IllegalStateException("database is down"));

		UserDeletionResponse response = service.delete(admin.getId(), target.getId(), EMAIL);

		assertThat(response).isEqualTo(new UserDeletionResponse(target.getId(), true));
	}

	@Test
	void aDeletionWithoutTheTypedAddressIsRefusedBeforeFirebaseIsAsked() {
		when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(target));

		assertThatThrownBy(() -> service.delete(admin.getId(), target.getId(), null))
				.isInstanceOfSatisfying(InvalidRequestException.class,
						error -> assertThat(error.code()).isEqualTo(UserDeletionService.CODE_EMAIL_MISMATCH));
		verify(loginProvisioner, never()).uidForEmail(anyString());
		verify(eraser, never()).erase(any(), any(), any(), any());
	}

	@Test
	void theTypedAddressMayDifferInCaseAndSurroundingSpaces() {
		when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(target));
		when(loginProvisioner.uidForEmail(EMAIL)).thenReturn(Optional.empty());
		when(appUserRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
		when(eraser.erase(same(target), same(admin), eq(Set.of("uid-stored")), any(Instant.class)))
				.thenReturn(auditId);
		when(cleanupService.runFor(auditId)).thenReturn(false);

		assertThat(service.delete(admin.getId(), target.getId(), "  Deniz@DONGU.co ").deletedUserId())
				.isEqualTo(target.getId());
	}

	private static AppUser user(UserRole role, String email, String firebaseUid) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setEmail(email);
		user.setFullName("Deniz Kaya");
		user.setRole(role);
		user.setFirebaseUid(firebaseUid);
		return user;
	}
}
