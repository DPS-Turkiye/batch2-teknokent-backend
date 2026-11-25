package com.dps.teknokent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Runs only with the activity mails on, so turning them on later still reaches everyone who is waiting. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.mail.activity", name = "enabled", havingValue = "true")
public class RequestFollowUpScheduler {

	private final RequestFollowUpService followUpService;

	@Scheduled(fixedDelayString = "${app.mail.activity.interval-ms:60000}")
	public void followUp() {
		followUpService.followUp();
	}
}
