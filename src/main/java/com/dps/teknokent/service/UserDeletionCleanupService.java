package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dps.teknokent.entity.UserDeletionCleanup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.UserDeletionAuditRepository;
import com.dps.teknokent.repository.UserDeletionCleanupRepository;
import com.dps.teknokent.security.LoginProvisioner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes what a committed user deletion left outside the database: the Firebase login and the stored files.
 * Nothing here runs inside the deletion's transaction, so a slow or failing Firebase or S3 never holds or undoes
 * it; a job that fails stays in its table and is tried again later. Both removals are safe to repeat.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDeletionCleanupService {

	/** From this many failures on, each further one is logged as an error: someone has to look. */
	static final int ATTEMPTS_BEFORE_ALARM = 10;
	/** The width of user_deletion_cleanup.last_error. */
	private static final int MAX_ERROR_LENGTH = 255;
	private static final Duration FIRST_RETRY = Duration.ofMinutes(15);
	private static final Duration LONGEST_RETRY = Duration.ofHours(6);

	private final UserDeletionCleanupRepository cleanupRepository;
	private final UserDeletionAuditRepository auditRepository;
	private final AppUserRepository appUserRepository;
	private final LoginProvisioner loginProvisioner;
	private final ObjectStorage objectStorage;

	/** Right after the deletion committed. True when something is still left for the scheduler. */
	public boolean runFor(UUID auditId) {
		cleanupRepository.findByAuditIdOrderByCreatedAtAsc(auditId).forEach(this::attempt);
		return !completeIfDone(auditId);
	}

	/** The scheduler's pass over every job that is due again. */
	public void retryDue() {
		// A job is never given up: its target (a uid, a file name) is personal data until the job has succeeded.
		List<UserDeletionCleanup> due = cleanupRepository
				.findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(Instant.now());
		due.forEach(this::attempt);
		due.stream().map(UserDeletionCleanup::getAuditId).distinct().forEach(this::completeIfDone);
	}

	private void attempt(UserDeletionCleanup job) {
		try {
			switch (job.getKind()) {
				case FIREBASE_LOGIN -> deleteLogin(job);
				case STORED_OBJECT -> objectStorage.delete(job.getTarget());
			}
			cleanupRepository.deleteById(job.getId());
		} catch (RuntimeException error) {
			int attempts = job.getAttempts() + 1;
			String reason = describe(error);
			cleanupRepository.recordFailure(job.getId(), attempts, reason, Instant.now().plus(delayAfter(attempts)));
			if (attempts >= ATTEMPTS_BEFORE_ALARM) {
				log.error("User deletion cleanup {} ({}) of audit {} still failing after {} attempts: {}",
						job.getId(), job.getKind(), job.getAuditId(), attempts, reason);
			} else {
				log.warn("User deletion cleanup {} ({}) of audit {} failed, attempt {}: {}",
						job.getId(), job.getKind(), job.getAuditId(), attempts, reason);
			}
		}
	}

	/**
	 * A login that an account uses again is left alone: the person signed up afresh with the same login before
	 * this job ran, and deleting it now would lock the new account out.
	 */
	private void deleteLogin(UserDeletionCleanup job) {
		if (appUserRepository.findByFirebaseUid(job.getTarget()).isPresent()) {
			log.warn("User deletion cleanup {} of audit {}: the login is used by an account again and is kept",
					job.getId(), job.getAuditId());
			return;
		}
		loginProvisioner.deleteLogin(job.getTarget());
	}

	private boolean completeIfDone(UUID auditId) {
		if (cleanupRepository.existsByAuditId(auditId)) {
			return false;
		}
		auditRepository.findById(auditId)
				.filter(audit -> audit.getCleanupCompletedAt() == null)
				.ifPresent(audit -> {
					audit.setCleanupCompletedAt(Instant.now());
					auditRepository.save(audit);
				});
		return true;
	}

	/**
	 * What is stored and logged about a failure. Only our own two exceptions carry a message written here, which
	 * names no person; a message from a library may quote the uid or the file name, so it is left out.
	 */
	private static String describe(RuntimeException error) {
		String type = error.getClass().getSimpleName();
		boolean ownMessage = error instanceof IllegalStateException || error instanceof InvalidRequestException;
		String text = ownMessage && error.getMessage() != null ? type + ": " + error.getMessage() : type;
		return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
	}

	private static Duration delayAfter(int attempts) {
		Duration delay = FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 10));
		return delay.compareTo(LONGEST_RETRY) > 0 ? LONGEST_RETRY : delay;
	}
}
