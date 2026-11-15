package com.dps.teknokent.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The reminder and notification-mail jobs, each on its own schedule: one that fails is logged and tried again on its
 * next run, and never stops the others from running.
 */
@Component
@ConditionalOnProperty(prefix = "app.notifications.reminders", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationReminderScheduler {

	private static final Logger log = LoggerFactory.getLogger(NotificationReminderScheduler.class);

	private final NotificationReminderService reminderService;
	private final OfficeHoursReminderService officeHoursReminderService;
	private final OfficeHoursMailOutboxService officeHoursMailOutbox;
	private final Clock clock;

	@Autowired
	public NotificationReminderScheduler(NotificationReminderService reminderService,
			OfficeHoursReminderService officeHoursReminderService, OfficeHoursMailOutboxService officeHoursMailOutbox) {
		this(reminderService, officeHoursReminderService, officeHoursMailOutbox, Clock.systemUTC());
	}

	NotificationReminderScheduler(NotificationReminderService reminderService,
			OfficeHoursReminderService officeHoursReminderService, OfficeHoursMailOutboxService officeHoursMailOutbox,
			Clock clock) {
		this.reminderService = reminderService;
		this.officeHoursReminderService = officeHoursReminderService;
		this.officeHoursMailOutbox = officeHoursMailOutbox;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.notifications.reminders.interval-ms:60000}")
	public void sendDueReminders() {
		try {
			reminderService.sendDueReminders();
		} catch (RuntimeException failure) {
			log.warn("Meeting and task reminders failed; trying again on the next run", failure);
		}
	}

	@Scheduled(fixedDelayString = "${app.notifications.reminders.interval-ms:60000}")
	public void remindOfficeHoursMeetings() {
		try {
			officeHoursReminderService.remindUpcomingMeetings(clock.instant());
		} catch (RuntimeException failure) {
			log.warn("Office hours meeting reminders failed; trying again on the next run", failure);
		}
	}

	@Scheduled(fixedDelayString = "${app.notifications.reminders.interval-ms:60000}")
	public void deliverOfficeHoursMail() {
		try {
			officeHoursMailOutbox.deliverDue(clock.instant());
		} catch (RuntimeException failure) {
			log.warn("Office hours mail delivery failed; trying again on the next run", failure);
		}
	}
}
