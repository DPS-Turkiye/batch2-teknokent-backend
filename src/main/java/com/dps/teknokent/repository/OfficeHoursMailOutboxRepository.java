package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.OfficeHoursMailOutbox;

public interface OfficeHoursMailOutboxRepository extends JpaRepository<OfficeHoursMailOutbox, UUID> {

	/** The oldest mails due now, a batch at a time so one run never holds a long list. */
	List<OfficeHoursMailOutbox> findTop50ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(Instant now);

	/**
	 * Takes a mail for this run: pushes its next attempt past the send and counts the attempt, unless another server
	 * took it first. 1 means this run sends it. Its own transaction, so the other server sees the claim before the
	 * slow send starts.
	 */
	@Transactional
	@Modifying
	@Query("""
			update OfficeHoursMailOutbox m set m.nextAttemptAt = :leaseUntil, m.attempts = m.attempts + 1
			where m.id = :id and m.nextAttemptAt = :seenAt
			""")
	int claim(@Param("id") UUID id, @Param("seenAt") Instant seenAt, @Param("leaseUntil") Instant leaseUntil);

	@Transactional
	@Modifying
	@Query("update OfficeHoursMailOutbox m set m.nextAttemptAt = :at where m.id = :id")
	int retryAt(@Param("id") UUID id, @Param("at") Instant at);

	@Transactional
	@Modifying
	@Query("delete from OfficeHoursMailOutbox m where m.id = :id")
	int deleteSent(@Param("id") UUID id);
}
