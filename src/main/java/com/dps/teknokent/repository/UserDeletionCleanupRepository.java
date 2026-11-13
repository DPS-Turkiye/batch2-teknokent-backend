package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.UserDeletionCleanup;

public interface UserDeletionCleanupRepository extends JpaRepository<UserDeletionCleanup, UUID> {

	List<UserDeletionCleanup> findByAuditIdOrderByCreatedAtAsc(UUID auditId);

	List<UserDeletionCleanup> findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(Instant now);

	boolean existsByAuditId(UUID auditId);

	/** An UPDATE, not a save: a job another pass removed meanwhile must stay removed. */
	@Modifying
	@Transactional
	@Query("update UserDeletionCleanup c set c.attempts = :attempts, c.lastError = :error, c.nextAttemptAt = :next "
			+ "where c.id = :id")
	void recordFailure(@Param("id") UUID id, @Param("attempts") int attempts, @Param("error") String error,
			@Param("next") Instant next);
}
