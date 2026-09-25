package com.dps.teknokent.service;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "app.notifications.reminders",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public class NotificationReminderScheduler {

	private final NotificationReminderService reminderService;

	@Scheduled(fixedDelayString = "${app.notifications.reminders.interval-ms:60000}")
	public void sendDueReminders() {
		Instant now = Instant.now();
		reminderService.remindUpcomingMeetings(now);
		reminderService.remindTaskDeadlines(now);
	}
}
