package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferencesRequest(
		@NotNull Boolean messages,
		@NotNull Boolean meetingReminders,
		@NotNull Boolean meetingChanges,
		@NotNull Boolean newTasks,
		@NotNull Boolean taskDeadlines,
		@NotNull Boolean startupActivity) {
}
