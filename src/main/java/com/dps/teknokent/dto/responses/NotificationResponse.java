package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.Notification;

public record NotificationResponse(
		UUID id,
		String title,
		String body,
		String href,
		Instant readAt,
		Instant createdAt) {

	public static NotificationResponse fromEntity(Notification notification) {
		return new NotificationResponse(
				notification.getId(),
				notification.getTitle(),
				notification.getBody(),
				notification.getHref(),
				notification.getReadAt(),
				notification.getCreatedAt());
	}
}
