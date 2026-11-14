package com.dps.teknokent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class NotificationReminderSchedulerTest {

	@Test
	void eachTickSendsTheDueRemindersAndOfficeHoursMail() {
		NotificationReminderService reminderService = mock(NotificationReminderService.class);
		OfficeHoursReminderService officeHoursReminders = mock(OfficeHoursReminderService.class);
		OfficeHoursMailOutboxService officeHoursOutbox = mock(OfficeHoursMailOutboxService.class);

		new NotificationReminderScheduler(reminderService, officeHoursReminders, officeHoursOutbox).sendDueReminders();

		verify(reminderService).sendDueReminders();
		verify(officeHoursReminders).remindUpcomingMeetings(any());
		verify(officeHoursOutbox).deliverDue(any());
	}
}
