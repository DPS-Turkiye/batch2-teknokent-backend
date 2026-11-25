package com.dps.teknokent.dto.requests;

import org.jspecify.annotations.Nullable;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferencesRequest(
		@NotNull Boolean messages,
		@NotNull Boolean meetingReminders,
		@NotNull Boolean meetingChanges,
		@NotNull Boolean newTasks,
		@NotNull Boolean taskDeadlines,
		@NotNull Boolean startupActivity,
		@Nullable Boolean emails) {
}
