package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class NotificationReminderSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");

	private final NotificationReminderService reminderService = mock(NotificationReminderService.class);
	private final OfficeHoursReminderService officeHoursReminders = mock(OfficeHoursReminderService.class);
	private final OfficeHoursMailOutboxService officeHoursOutbox = mock(OfficeHoursMailOutboxService.class);
	private final NotificationReminderScheduler scheduler = new NotificationReminderScheduler(reminderService,
			officeHoursReminders, officeHoursOutbox, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void eachJobRunsItsOwnWork() {
		scheduler.sendDueReminders();
		scheduler.remindOfficeHoursMeetings();
		scheduler.deliverOfficeHoursMail();

		verify(reminderService).sendDueReminders();
		verify(officeHoursReminders).remindUpcomingMeetings(NOW);
		verify(officeHoursOutbox).deliverDue(NOW);
	}

	@Test
	void aJobThatFailsIsLoggedAndLeavesTheOthersToRun() {
		doThrow(new IllegalStateException("database down")).when(reminderService).sendDueReminders();
		doThrow(new IllegalStateException("database down")).when(officeHoursReminders).remindUpcomingMeetings(NOW);

		assertThatCode(scheduler::sendDueReminders).doesNotThrowAnyException();
		assertThatCode(scheduler::remindOfficeHoursMeetings).doesNotThrowAnyException();
		scheduler.deliverOfficeHoursMail();

		verify(officeHoursOutbox).deliverDue(NOW);
	}
}
