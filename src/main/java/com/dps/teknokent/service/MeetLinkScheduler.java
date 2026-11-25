package com.dps.teknokent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-meet", name = "enabled", havingValue = "true")
public class MeetLinkScheduler {

	private final MeetLinkService meetLinkService;

	@Scheduled(fixedDelayString = "${app.google-meet.interval-ms:30000}")
	public void createMissingLinks() {
		meetLinkService.createMissingLinks();
	}
}
