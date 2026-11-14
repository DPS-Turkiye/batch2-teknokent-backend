package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserDeletionCleanupKind;
import com.dps.teknokent.entity.UserDeletionAudit;
import com.dps.teknokent.entity.UserDeletionCleanup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.UserDeletionAuditRepository;
import com.dps.teknokent.repository.UserDeletionCleanupRepository;
import com.dps.teknokent.security.LoginProvisioner;

@ExtendWith(MockitoExtension.class)
class UserDeletionCleanupServiceTest {

	private static final String FILE = "documents/u1/cv/ayse-yilmaz-cv.pdf";

	@Mock
	private UserDeletionCleanupRepository cleanupRepository;
	@Mock
	private UserDeletionAuditRepository auditRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private LoginProvisioner loginProvisioner;
	@Mock
	private ObjectStorage objectStorage;
	@InjectMocks
	private UserDeletionCleanupService service;

	private final UUID auditId = UUID.randomUUID();

	@Test
	void aJobThatSucceedsIsRemovedAndTheLastOneCompletesTheAudit() {
		UserDeletionCleanup file = job(UserDeletionCleanupKind.STORED_OBJECT, FILE, 0);
		UserDeletionCleanup login = job(UserDeletionCleanupKind.FIREBASE_LOGIN, "uid-1", 0);
		UserDeletionAudit audit = new UserDeletionAudit(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
		when(cleanupRepository.findByAuditIdOrderByCreatedAtAsc(auditId)).thenReturn(List.of(file, login));
		when(appUserRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());
		when(cleanupRepository.existsByAuditId(auditId)).thenReturn(false);
		when(auditRepository.findById(auditId)).thenReturn(Optional.of(audit));

		boolean pending = service.runFor(auditId);

		assertThat(pending).isFalse();
		verify(objectStorage).delete(FILE);
		verify(loginProvisioner).deleteLogin("uid-1");
		verify(cleanupRepository).deleteById(file.getId());
		verify(cleanupRepository).deleteById(login.getId());
		assertThat(audit.getCleanupCompletedAt()).isNotNull();
		verify(auditRepository).save(audit);
	}

	@Test
	void aJobThatFailsStaysForTheSchedulerAndDoesNotStopTheOthers() {
		UserDeletionCleanup file = job(UserDeletionCleanupKind.STORED_OBJECT, FILE, 0);
		UserDeletionCleanup other = job(UserDeletionCleanupKind.STORED_OBJECT, "documents/u1/avatar/a.png", 0);
		when(cleanupRepository.findByAuditIdOrderByCreatedAtAsc(auditId)).thenReturn(List.of(file, other));
		doThrow(new IllegalStateException("S3 is not configured")).when(objectStorage).delete(FILE);
		when(cleanupRepository.existsByAuditId(auditId)).thenReturn(true);

		boolean pending = service.runFor(auditId);

		assertThat(pending).isTrue();
		verify(cleanupRepository, never()).deleteById(file.getId());
		verify(cleanupRepository).deleteById(other.getId());
		verify(cleanupRepository).recordFailure(eq(file.getId()), eq(1),
				eq("IllegalStateException: S3 is not configured"),
				any(Instant.class));
		verify(auditRepository, never()).save(any());
	}

	@Test
	void eachFailureWaitsTwiceAsLongAsTheLastUpToSixHours() {
		assertThat(delayAfterFailing(0)).isEqualTo(Duration.ofMinutes(15));
		assertThat(delayAfterFailing(1)).isEqualTo(Duration.ofMinutes(30));
		assertThat(delayAfterFailing(4)).isEqualTo(Duration.ofHours(4));
		assertThat(delayAfterFailing(5)).isEqualTo(Duration.ofHours(6));
		// A job is never given up, so the count keeps growing; the wait must neither grow with it nor overflow.
		assertThat(delayAfterFailing(UserDeletionCleanupService.ATTEMPTS_BEFORE_ALARM)).isEqualTo(Duration.ofHours(6));
		assertThat(delayAfterFailing(500)).isEqualTo(Duration.ofHours(6));
	}

	// A message from Firebase or S3 may quote the uid or the file name, which is the personal data being removed.
	@Test
	void theStoredReasonCarriesOnlyOurOwnMessagesNeverALibrarys() {
		assertThat(reasonFor(new RuntimeException("NoSuchKey: " + FILE))).isEqualTo("RuntimeException");
		assertThat(reasonFor(new IllegalArgumentException("uid-1 is malformed"))).isEqualTo("IllegalArgumentException");
		assertThat(reasonFor(new IllegalStateException("Firebase could not delete the login: INTERNAL")))
				.isEqualTo("IllegalStateException: Firebase could not delete the login: INTERNAL");
		assertThat(reasonFor(new InvalidRequestException("Storage is not configured.")))
				.isEqualTo("InvalidRequestException: Storage is not configured.");
		assertThat(reasonFor(new IllegalStateException((String) null))).isEqualTo("IllegalStateException");
	}

	@Test
	void aReasonLongerThanItsColumnIsCut() {
		assertThat(reasonFor(new IllegalStateException("x".repeat(400)))).hasSize(255)
				.startsWith("IllegalStateException: xxx");
	}

	@Test
	void theSchedulerRetriesWhatIsDueAndCompletesEachAuditOnce() {
		UUID otherAuditId = UUID.randomUUID();
		UserDeletionCleanup first = job(UserDeletionCleanupKind.STORED_OBJECT, FILE, 2);
		UserDeletionCleanup second = job(UserDeletionCleanupKind.STORED_OBJECT, "documents/u1/avatar/a.png", 2);
		UserDeletionCleanup stillFailing = new UserDeletionCleanup(otherAuditId, UserDeletionCleanupKind.FIREBASE_LOGIN,
				"uid-2", Instant.now());
		UserDeletionAudit audit = new UserDeletionAudit(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
		when(cleanupRepository.findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(any(Instant.class)))
				.thenReturn(List.of(first, second, stillFailing));
		when(appUserRepository.findByFirebaseUid("uid-2")).thenReturn(Optional.empty());
		doThrow(new IllegalStateException("Firebase could not delete the login: UNAVAILABLE")).when(loginProvisioner)
				.deleteLogin("uid-2");
		when(cleanupRepository.existsByAuditId(auditId)).thenReturn(false);
		when(cleanupRepository.existsByAuditId(otherAuditId)).thenReturn(true);
		when(auditRepository.findById(auditId)).thenReturn(Optional.of(audit));

		service.retryDue();

		verify(auditRepository).findById(auditId);
		verify(auditRepository, never()).findById(otherAuditId);
		assertThat(audit.getCleanupCompletedAt()).isNotNull();
		verify(cleanupRepository).recordFailure(eq(stillFailing.getId()), eq(1), anyString(), any(Instant.class));
	}

	// The scheduler and the request that deleted the account may both finish an audit; the first time is kept.
	@Test
	void anAuditAlreadyMarkedCompleteKeepsItsTime() {
		Instant completedAt = Instant.parse("2026-11-01T10:00:00Z");
		UserDeletionAudit audit = new UserDeletionAudit(UUID.randomUUID(), UUID.randomUUID(), completedAt);
		audit.setCleanupCompletedAt(completedAt);
		when(cleanupRepository.findByAuditIdOrderByCreatedAtAsc(auditId)).thenReturn(List.of());
		when(cleanupRepository.existsByAuditId(auditId)).thenReturn(false);
		when(auditRepository.findById(auditId)).thenReturn(Optional.of(audit));

		assertThat(service.runFor(auditId)).isFalse();

		assertThat(audit.getCleanupCompletedAt()).isEqualTo(completedAt);
		verify(auditRepository, never()).save(any());
	}

	private Duration delayAfterFailing(int earlierAttempts) {
		UserDeletionCleanup job = failingJob(earlierAttempts, new IllegalStateException("S3 is not configured"));
		Instant before = Instant.now();

		service.runFor(auditId);

		ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
		verify(cleanupRepository).recordFailure(eq(job.getId()), eq(earlierAttempts + 1), anyString(), next.capture());
		// The service reads the clock a moment after this test did, so the wait is the whole minutes of the gap.
		return Duration.between(before, next.getValue()).truncatedTo(ChronoUnit.MINUTES);
	}

	private String reasonFor(RuntimeException error) {
		UserDeletionCleanup job = failingJob(0, error);

		service.runFor(auditId);

		ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(cleanupRepository).recordFailure(eq(job.getId()), anyInt(), reason.capture(), any(Instant.class));
		return reason.getValue();
	}

	private UserDeletionCleanup failingJob(int earlierAttempts, RuntimeException error) {
		UserDeletionCleanup job = job(UserDeletionCleanupKind.STORED_OBJECT, FILE, earlierAttempts);
		when(cleanupRepository.findByAuditIdOrderByCreatedAtAsc(auditId)).thenReturn(List.of(job));
		doThrow(error).when(objectStorage).delete(FILE);
		when(cleanupRepository.existsByAuditId(auditId)).thenReturn(true);
		return job;
	}

	private UserDeletionCleanup job(UserDeletionCleanupKind kind, String target, int attempts) {
		UserDeletionCleanup job = new UserDeletionCleanup(auditId, kind, target, Instant.now());
		job.setAttempts(attempts);
		return job;
	}
}
