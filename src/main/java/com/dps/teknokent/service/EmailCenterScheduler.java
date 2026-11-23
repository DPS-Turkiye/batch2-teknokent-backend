package com.dps.teknokent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.mail.center", name = "enabled", havingValue = "true")
public class EmailCenterScheduler {

	private final EmailCampaignWorker worker;

	@Scheduled(fixedDelayString = "${app.mail.center.interval-ms:1000}")
	public void sendDue() {
		worker.run();
	}
}
