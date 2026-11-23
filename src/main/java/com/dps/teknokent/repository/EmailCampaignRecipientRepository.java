package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.entity.EmailCampaignRecipient;

public interface EmailCampaignRecipientRepository extends JpaRepository<EmailCampaignRecipient, UUID> {

	/** How many recipients of a mail have a status. */
	interface StatusCount {

		UUID getCampaignId();

		EmailRecipientStatus getStatus();

		long getTotal();
	}

	List<EmailCampaignRecipient> findByCampaignIdOrderByFullNameAsc(UUID campaignId);

	Optional<EmailCampaignRecipient> findBySesMessageId(String sesMessageId);

	Optional<EmailCampaignRecipient> findByUnsubscribeTokenHash(String unsubscribeTokenHash);

	List<EmailCampaignRecipient> findByAddressAndStatusInAndSentAtAfter(
			String address, Collection<EmailRecipientStatus> statuses, Instant sentAfter);

	/** The mails with problems their sender and the admins have not been told about yet. */
	@Query("""
			SELECT c.id FROM EmailCampaign c
			WHERE (c.lastAlertAt IS NULL OR c.lastAlertAt <= :quietSince)
				AND c.alertedProblems < (SELECT COUNT(r) FROM EmailCampaignRecipient r
					WHERE r.campaignId = c.id AND r.status IN :problems)
			""")
	List<UUID> findCampaignsToAlert(
			@Param("quietSince") Instant quietSince,
			@Param("problems") Collection<EmailRecipientStatus> problems);

	long countByCampaignIdAndStatusIn(UUID campaignId, Collection<EmailRecipientStatus> statuses);

	boolean existsByCampaignIdAndStatusIn(UUID campaignId, Collection<EmailRecipientStatus> statuses);

	@Query("""
			SELECT r.campaignId AS campaignId, r.status AS status, COUNT(r) AS total
			FROM EmailCampaignRecipient r
			WHERE r.campaignId IN :campaignIds
			GROUP BY r.campaignId, r.status
			""")
	List<StatusCount> countByStatus(@Param("campaignIds") Collection<UUID> campaignIds);

	@Query("""
			SELECT r.id FROM EmailCampaignRecipient r
			WHERE r.status = com.dps.teknokent.domain.EmailRecipientStatus.PENDING AND r.nextAttemptAt <= :now
			ORDER BY r.nextAttemptAt, r.id
			""")
	List<UUID> findDueIds(@Param("now") Instant now, Pageable page);

	/** 1 for the caller that took the row; 0 when it is no longer waiting. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE EmailCampaignRecipient r
			SET r.status = com.dps.teknokent.domain.EmailRecipientStatus.SENDING, r.claimedAt = :now,
				r.attempts = r.attempts + 1
			WHERE r.id = :id AND r.status = com.dps.teknokent.domain.EmailRecipientStatus.PENDING
			""")
	int claim(@Param("id") UUID id, @Param("now") Instant now);

	/** Whether SES took a mail whose outcome was never recorded is unknown, so it is never sent again by itself. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE EmailCampaignRecipient r
			SET r.status = com.dps.teknokent.domain.EmailRecipientStatus.FAILED, r.errorCode = :code
			WHERE r.status = com.dps.teknokent.domain.EmailRecipientStatus.SENDING AND r.claimedAt < :before
			""")
	int failStale(@Param("before") Instant before, @Param("code") String code);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("DELETE FROM EmailCampaignRecipient r WHERE r.campaignId = :campaignId")
	void deleteByCampaignId(@Param("campaignId") UUID campaignId);
}
