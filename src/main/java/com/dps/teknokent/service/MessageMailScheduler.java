package com.dps.teknokent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Runs only with the activity mails on, like {@link RequestFollowUpScheduler}. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.mail.activity", name = "enabled", havingValue = "true")
public class MessageMailScheduler {

	private final MessageMailService messageMailService;

	@Scheduled(fixedDelayString = "${app.mail.activity.interval-ms:60000}")
	public void mailUnreadMessages() {
		messageMailService.mailUnreadMessages();
	}
}
