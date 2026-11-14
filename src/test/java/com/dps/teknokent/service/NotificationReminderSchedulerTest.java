package com.dps.teknokent.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class NotificationReminderSchedulerTest {

	@Test
	void eachTickSendsTheDueReminders() {
		NotificationReminderService reminderService = mock(NotificationReminderService.class);

		new NotificationReminderScheduler(reminderService).sendDueReminders();

		verify(reminderService).sendDueReminders();
	}
}
