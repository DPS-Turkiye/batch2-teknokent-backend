package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
	List<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId);
	Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);
	long countByRecipientIdAndReadAtIsNull(UUID recipientId);

	@Modifying
	@Query("update Notification n set n.readAt = :readAt where n.recipient.id = :recipientId and n.readAt is null")
	int markAllRead(@Param("recipientId") UUID recipientId, @Param("readAt") Instant readAt);
}
