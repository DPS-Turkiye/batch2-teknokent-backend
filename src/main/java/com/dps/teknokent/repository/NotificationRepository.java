package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
	List<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId);
	Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);
}
