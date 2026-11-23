package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.entity.EmailCampaign;

import jakarta.persistence.LockModeType;

public interface EmailCampaignRepository extends JpaRepository<EmailCampaign, UUID> {

	List<EmailCampaign> findTop100ByOrderByCreatedAtDesc();

	List<EmailCampaign> findTop100ByCreatedByOrderByCreatedAtDesc(UUID createdBy);

	/** Only the two columns: an alert may be written to the same mail meanwhile. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE EmailCampaign c
			SET c.status = com.dps.teknokent.domain.EmailCampaignStatus.SENT, c.finishedAt = :now
			WHERE c.status = com.dps.teknokent.domain.EmailCampaignStatus.SENDING
				AND NOT EXISTS (SELECT r.id FROM EmailCampaignRecipient r
					WHERE r.campaignId = c.id AND r.status IN :waiting)
			""")
	int finishDone(@Param("waiting") Collection<EmailRecipientStatus> waiting, @Param("now") Instant now);

	/** Locked until the transaction ends, so a mail is edited, reviewed or confirmed by one request at a time. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from EmailCampaign c where c.id = :id")
	Optional<EmailCampaign> findByIdForUpdate(@Param("id") UUID id);
}
