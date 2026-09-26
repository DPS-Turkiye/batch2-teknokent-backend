package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.dto.provider.PushMessage;
import com.dps.teknokent.dto.responses.NotificationResponse;
import com.dps.teknokent.dto.responses.NotificationUnreadCountResponse;
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
	private final ApplicationEventPublisher eventPublisher;

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

	@Transactional(readOnly = true)
	public NotificationUnreadCountResponse unreadCount(UUID recipientId) {
		return new NotificationUnreadCountResponse(
				notificationRepository.countByRecipientIdAndReadAtIsNull(recipientId));
	}

	@Transactional
	public void markAllRead(UUID recipientId) {
		notificationRepository.markAllRead(recipientId, Instant.now());
	}

	@Transactional
	public void delete(UUID recipientId, UUID notificationId) {
		notificationRepository.delete(requireOwned(recipientId, notificationId));
	}

	@Transactional
	public Notification create(AppUser recipient, String title, String body, String href) {
		return create(recipient, NotificationCategory.GENERAL, title, body, href);
	}

	@Transactional
	public Notification create(AppUser recipient, NotificationCategory category, String title, String body,
			String href) {
		Notification notification = new Notification();
		notification.setId(UUID.randomUUID());
		notification.setRecipient(recipient);
		notification.setCategory(category);
		notification.setTitle(TextUtils.trim(title));
		notification.setBody(TextUtils.trim(body));
		notification.setHref(TextUtils.normalizeOptional(href));
		notificationRepository.save(notification);
		eventPublisher.publishEvent(new NotificationCreatedEvent(recipient.getId(), new PushMessage(
				notification.getId(),
				category,
				notification.getTitle(),
				notification.getBody(),
				notification.getHref())));
		return notification;
	}

	private Notification requireOwned(UUID recipientId, UUID notificationId) {
		return notificationRepository.findByIdAndRecipientId(notificationId, recipientId)
				.orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));
	}
}
