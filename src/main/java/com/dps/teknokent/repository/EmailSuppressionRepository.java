package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.EmailSuppressionReason;
import com.dps.teknokent.entity.EmailSuppression;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, UUID> {

	boolean existsByAddressHash(String addressHash);

	/** Two writers may meet here (an unsubscribe and a bounce); the second one changes nothing. */
	@Modifying(flushAutomatically = true)
	@Query(nativeQuery = true, value = "INSERT INTO email_suppression (id, address_hash, reason, created_at) "
			+ "VALUES (:id, :hash, :reason, :now) ON CONFLICT DO NOTHING")
	int addIfMissing(
			@Param("id") UUID id,
			@Param("hash") String hash,
			@Param("reason") String reason,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE EmailSuppression s SET s.reason = :reason WHERE s.addressHash = :hash")
	void changeReason(@Param("hash") String hash, @Param("reason") EmailSuppressionReason reason);

	List<EmailSuppression> findByAddressHashIn(Collection<String> addressHashes);
}
