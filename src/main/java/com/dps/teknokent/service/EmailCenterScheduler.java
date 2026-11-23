package com.dps.teknokent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Bounces and alerts go on while sending waits, for mails that left earlier. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.mail.center", name = "enabled", havingValue = "true")
public class EmailCenterScheduler {

	private final EmailCampaignWorker worker;
	private final EmailAlertService alertService;
	private final EmailBounceMonitor bounceMonitor;

	@Scheduled(fixedDelayString = "${app.mail.center.interval-ms:1000}")
	public void sendDue() {
		worker.run();
	}

	@Scheduled(fixedDelayString = "${app.mail.center.bounce-interval-ms:120000}")
	public void readBounces() {
		bounceMonitor.read();
	}

	@Scheduled(fixedDelayString = "${app.mail.center.alert-interval-ms:60000}")
	public void alertProblems() {
		alertService.alertProblems();
	}
}
