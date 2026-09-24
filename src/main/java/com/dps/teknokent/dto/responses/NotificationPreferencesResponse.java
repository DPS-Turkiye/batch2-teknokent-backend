package com.dps.teknokent.dto.responses;

import com.dps.teknokent.entity.NotificationPreference;

public record NotificationPreferencesResponse(
		boolean messages,
		boolean meetingReminders,
		boolean meetingChanges,
		boolean newTasks,
		boolean taskDeadlines,
		boolean startupActivity) {

	public static NotificationPreferencesResponse fromEntity(NotificationPreference preference) {
		return new NotificationPreferencesResponse(
				preference.isMessages(),
				preference.isMeetingReminders(),
				preference.isMeetingChanges(),
				preference.isNewTasks(),
				preference.isTaskDeadlines(),
				preference.isStartupActivity());
	}
}
