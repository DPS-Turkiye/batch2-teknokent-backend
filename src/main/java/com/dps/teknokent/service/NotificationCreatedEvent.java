package com.dps.teknokent.service;

import java.util.UUID;

import com.dps.teknokent.dto.provider.PushMessage;

public record NotificationCreatedEvent(UUID recipientId, PushMessage message) {
}
