package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AccountDeletionBlock;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.dto.responses.SelfDeletionOptionsResponse;
import com.dps.teknokent.dto.responses.StartupDeletionResponse;
import com.dps.teknokent.dto.responses.UserDeletionResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;

/**
 * A person deletes their own account, or a founder their startup, from their profile. The caller is always the
 * verified token's owner, never an id from the request. Like an admin's deletion, the database commits first and the
 * stored files are removed after it, retried until they are gone.
 */
@Slf4j
@Service
public class SelfDeletionService {

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final SelfDeletionPolicy policy;
	private final UserDeletionService userDeletionService;
	private final OrganizationDataEraser organizationEraser;
	private final UserDeletionCleanupService cleanupService;
	private final TransactionTemplate transaction;
	private final TransactionTemplate readOnlyTransaction;

	public SelfDeletionService(
			AppUserRepository appUserRepository,
			OrganizationRepository organizationRepository,
			SelfDeletionPolicy policy,
			UserDeletionService userDeletionService,
			OrganizationDataEraser organizationEraser,
			UserDeletionCleanupService cleanupService,
			PlatformTransactionManager transactionManager) {
		this.appUserRepository = appUserRepository;
		this.organizationRepository = organizationRepository;
		this.policy = policy;
		this.userDeletionService = userDeletionService;
		this.organizationEraser = organizationEraser;
		this.cleanupService = cleanupService;
		this.transaction = new TransactionTemplate(transactionManager);
		this.readOnlyTransaction = new TransactionTemplate(transactionManager);
		this.readOnlyTransaction.setReadOnly(true);
	}

	public SelfDeletionOptionsResponse options(UUID actorId) {
		return readOnlyTransaction.execute(status -> {
			AppUser self = requireApproved(actorId);
			Optional<AccountDeletionBlock> block = policy.accountBlock(self);
			SelfDeletionOptionsResponse.Startup startup = policy.deletableStartup(self)
					.map(organization -> new SelfDeletionOptionsResponse.Startup(organization.getId(),
							organization.getName()))
					.orElse(null);
			return new SelfDeletionOptionsResponse(
					new SelfDeletionOptionsResponse.Account(block.isEmpty(), block.orElse(null)), startup);
		});
	}

	public UserDeletionResponse deleteAccount(UUID actorId, @Nullable Instant signedInAt, String confirmation) {
		return userDeletionService.deleteSelf(actorId, signedInAt, confirmation);
	}

	/** Not transactional itself: the storage calls must stay outside the database transaction. */
	public StartupDeletionResponse deleteStartup(UUID actorId, @Nullable Instant signedInAt, String confirmName) {
		Deleted deleted = transaction.execute(status -> {
			AppUser founder = appUserRepository.findByIdForUpdate(actorId)
					.filter(user -> user.getStatus() == UserAccountStatus.APPROVED)
					.orElseThrow(SelfDeletionService::signInRequired);
			Organization current = founder.getOrganization();
			// Locked before the checks, so a concurrent change of leader or a second deletion waits for this one.
			Organization startup = current == null ? null
					: organizationRepository.findByIdForUpdate(current.getId()).orElse(null);
			if (startup == null || policy.deletableStartup(founder).isEmpty()) {
				throw new ResourceNotFoundException("Startup not found");
			}
			policy.requireRecentSignIn(signedInAt);
			if (!SelfDeletionPolicy.confirmsStartup(confirmName, startup.getName())) {
				throw new InvalidRequestException(Texts.forRequest("error.startupDeleteConfirmation"),
						SelfDeletionPolicy.CODE_CONFIRMATION);
			}
			UUID organizationId = startup.getId();
			return new Deleted(organizationId, organizationEraser.erase(startup, founder, Instant.now()));
		});
		log.info("Founder {} deleted startup {} (audit {})", actorId, deleted.organizationId(), deleted.auditId());

		boolean cleanupPending;
		try {
			cleanupPending = cleanupService.runFor(deleted.auditId());
		} catch (RuntimeException error) {
			// The startup is gone either way; the scheduler finishes what is left.
			log.warn("Cleanup after deleting startup {} (audit {}) will be retried: {}", deleted.organizationId(),
					deleted.auditId(), error.getClass().getSimpleName());
			cleanupPending = true;
		}
		return new StartupDeletionResponse(deleted.organizationId(), cleanupPending);
	}

	private AppUser requireApproved(UUID actorId) {
		return appUserRepository.findById(actorId)
				.filter(user -> user.getStatus() == UserAccountStatus.APPROVED)
				.orElseThrow(SelfDeletionService::signInRequired);
	}

	private static ResponseStatusException signInRequired() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.signInRequiredForAction"));
	}

	private record Deleted(UUID organizationId, UUID auditId) {
	}
}
