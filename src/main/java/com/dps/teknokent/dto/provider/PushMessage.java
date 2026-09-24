package com.dps.teknokent.dto.provider;

import java.util.UUID;

import com.dps.teknokent.domain.NotificationCategory;

public record PushMessage(
		UUID notificationId,
		NotificationCategory category,
		String title,
		String body,
		String href) {
}
