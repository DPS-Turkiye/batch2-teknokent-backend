package com.dps.teknokent.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserAccountStatus;
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
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;

/**
 * An admin deletes a user for good. The order is what makes a failure harmless:
 * <ol>
 * <li>the Firebase logins to remove are looked up first (a read; if Firebase cannot answer, nothing has changed);</li>
 * <li>one database transaction erases the account ({@link UserDataEraser}); it commits or leaves no trace;</li>
 * <li>only then are the Firebase login and the stored files removed, each retried until it is gone.</li>
 * </ol>
 * The database goes first because it is what grants access: once it commits, no login reaches the account, so a
 * Firebase or storage failure afterwards leaves an orphan to clean up and never a half-deleted user.
 */
@Slf4j
@Service
public class UserDeletionService {

	public static final String CODE_SELF = "USER_DELETE_SELF";
	public static final String CODE_ADMIN = "USER_DELETE_ADMIN";
	public static final String CODE_EMAIL_MISMATCH = "USER_DELETE_EMAIL_MISMATCH";
	public static final String CODE_ALREADY_DELETED = "USER_ALREADY_DELETED";
	public static final String CODE_INSTITUTION_LINKED = "USER_DELETE_INSTITUTION_LINKED";

	private final AppUserRepository appUserRepository;
	private final AppUserIdentityRepository identityRepository;
	private final InstitutionPersonRepository institutionPersonRepository;
	private final UserDataEraser eraser;
	private final LoginProvisioner loginProvisioner;
	private final UserDeletionCleanupService cleanupService;
	private final TransactionTemplate transaction;
	private final TransactionTemplate readOnlyTransaction;

	public UserDeletionService(
			AppUserRepository appUserRepository,
			AppUserIdentityRepository identityRepository,
			InstitutionPersonRepository institutionPersonRepository,
			UserDataEraser eraser,
			LoginProvisioner loginProvisioner,
			UserDeletionCleanupService cleanupService,
			PlatformTransactionManager transactionManager) {
		this.appUserRepository = appUserRepository;
		this.identityRepository = identityRepository;
		this.institutionPersonRepository = institutionPersonRepository;
		this.eraser = eraser;
		this.loginProvisioner = loginProvisioner;
		this.cleanupService = cleanupService;
		this.transaction = new TransactionTemplate(transactionManager);
		this.readOnlyTransaction = new TransactionTemplate(transactionManager);
		this.readOnlyTransaction.setReadOnly(true);
	}

	/**
	 * {@code confirmEmail} is what the admin typed into the confirmation; it must be the target's address. Not
	 * transactional itself: the Firebase and storage calls must stay outside the database transaction.
	 */
	public UserDeletionResponse delete(UUID actorId, UUID targetId, String confirmEmail) {
		Login login = readOnlyTransaction.execute(status -> {
			AppUser target = requireDeletable(actorId, targetId, confirmEmail);
			return new Login(target.getEmail(), target.getFirebaseUid());
		});

		// Resolved to uids now and removed by uid later: a login the person creates after this deletion has
		// another uid, so a retried cleanup can never delete it.
		Set<String> firebaseUids = new LinkedHashSet<>();
		if (login.firebaseUid() != null && !login.firebaseUid().isBlank()) {
			firebaseUids.add(login.firebaseUid());
		}
		loginProvisioner.uidForEmail(login.email()).ifPresent(firebaseUids::add);

		UUID auditId = transaction.execute(status -> {
			appUserRepository.findByIdForUpdate(targetId)
					.orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetId));
			// Checked again under the lock: another admin may have deleted or promoted the account meanwhile.
			AppUser target = requireDeletable(actorId, targetId, confirmEmail);
			if (target.getFirebaseUid() != null && !target.getFirebaseUid().isBlank()) {
				firebaseUids.add(target.getFirebaseUid());
			}
			AppUser actor = appUserRepository.findById(actorId).orElseThrow();
			return eraser.erase(target, actor, firebaseUids, Instant.now());
		});
		log.info("Admin {} deleted user {} (audit {})", actorId, targetId, auditId);

		boolean cleanupPending;
		try {
			cleanupPending = cleanupService.runFor(auditId);
		} catch (RuntimeException error) {
			// The account is gone either way; the scheduler finishes what is left.
			log.warn("Cleanup after deleting user {} (audit {}) will be retried: {}", targetId, auditId,
					error.getClass().getSimpleName());
			cleanupPending = true;
		}
		return new UserDeletionResponse(targetId, cleanupPending);
	}

	private AppUser requireDeletable(UUID actorId, UUID targetId, String confirmEmail) {
		AppUser actor = appUserRepository.findById(actorId)
				.filter(user -> user.getStatus() == UserAccountStatus.APPROVED && user.has(UserRole.ADMIN))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
						Texts.forRequest("error.userDeleteAdminOnly")));
		AppUser target = appUserRepository.findById(targetId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetId));
		if (target.getStatus() == UserAccountStatus.DELETED) {
			throw new InvalidRequestException(Texts.forRequest("error.userAlreadyDeleted"), CODE_ALREADY_DELETED);
		}
		if (target.getId().equals(actor.getId())) {
			throw new InvalidRequestException(Texts.forRequest("error.userDeleteSelf"), CODE_SELF);
		}
		// The primary role is checked next to the grants, so an admin whose grant row lapsed is still refused.
		if (target.getRole() == UserRole.ADMIN || target.has(UserRole.ADMIN)) {
			throw new InvalidRequestException(Texts.forRequest("error.userDeleteAdmin"), CODE_ADMIN);
		}
		// Deletion is built for Teknokent accounts only. An account tied to an ODTÜ roster record or ODTÜ login is
		// refused rather than erased, so the institution's data stays exactly as it is; OTG, Teknokent's own
		// institution sign-in, writes no roster record and its link goes with the account.
		if (institutionPersonRepository.existsByUserId(target.getId())
				|| identityRepository.existsByUserIdAndProviderNot(target.getId(), OtgLoginProvider.ID)) {
			throw new InvalidRequestException(Texts.forRequest("error.userDeleteInstitutionLinked"),
					CODE_INSTITUTION_LINKED);
		}
		if (confirmEmail == null || !confirmEmail.trim().equalsIgnoreCase(target.getEmail())) {
			throw new InvalidRequestException(Texts.forRequest("error.userDeleteEmailMismatch"), CODE_EMAIL_MISMATCH);
		}
		return target;
	}

	private record Login(String email, String firebaseUid) {
	}
}
