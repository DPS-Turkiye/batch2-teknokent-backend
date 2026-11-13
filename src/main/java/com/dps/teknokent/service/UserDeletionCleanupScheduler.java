package com.dps.teknokent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "app.user-deletion.cleanup",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public class UserDeletionCleanupScheduler {

	private final UserDeletionCleanupService cleanupService;

	@Scheduled(
			initialDelayString = "${app.user-deletion.cleanup.interval-ms:900000}",
			fixedDelayString = "${app.user-deletion.cleanup.interval-ms:900000}")
	public void retryDueCleanups() {
		cleanupService.retryDue();
	}
}
