package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.EmailCampaignStatus;
import com.dps.teknokent.entity.EmailCampaign;

import jakarta.persistence.LockModeType;

public interface EmailCampaignRepository extends JpaRepository<EmailCampaign, UUID> {

	List<EmailCampaign> findTop100ByOrderByCreatedAtDesc();

	List<EmailCampaign> findTop100ByCreatedByOrderByCreatedAtDesc(UUID createdBy);

	List<EmailCampaign> findByStatus(EmailCampaignStatus status);

	/** Locked until the transaction ends, so a mail is edited, reviewed or confirmed by one request at a time. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from EmailCampaign c where c.id = :id")
	Optional<EmailCampaign> findByIdForUpdate(@Param("id") UUID id);
}
