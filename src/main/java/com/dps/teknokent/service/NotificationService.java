package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.responses.NotificationResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notificationRepository;

	@Transactional(readOnly = true)
	public List<NotificationResponse> list(UUID recipientId) {
		return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId).stream()
				.map(NotificationResponse::fromEntity)
				.toList();
	}

	@Transactional
	public NotificationResponse markRead(UUID recipientId, UUID notificationId) {
		Notification notification = requireOwned(recipientId, notificationId);
		if (notification.getReadAt() == null) {
			notification.setReadAt(Instant.now());
		}
		return NotificationResponse.fromEntity(notificationRepository.save(notification));
	}

	@Transactional
	public void delete(UUID recipientId, UUID notificationId) {
		notificationRepository.delete(requireOwned(recipientId, notificationId));
	}

	@Transactional
	public void create(AppUser recipient, String title, String body, String href) {
		Notification notification = new Notification();
		notification.setId(UUID.randomUUID());
		notification.setRecipient(recipient);
		notification.setTitle(TextUtils.trim(title));
		notification.setBody(TextUtils.trim(body));
		notification.setHref(TextUtils.normalizeOptional(href));
		notificationRepository.save(notification);
	}

	private Notification requireOwned(UUID recipientId, UUID notificationId) {
		return notificationRepository.findByIdAndRecipientId(notificationId, recipientId)
				.orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));
	}
}
