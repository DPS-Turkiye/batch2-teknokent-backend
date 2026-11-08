package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UpdateTimestamp;

import com.dps.teknokent.domain.NotificationCategory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_preference")
@Getter
@Setter
@NoArgsConstructor
public class NotificationPreference {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Column(nullable = false)
	private boolean messages = true;

	@Column(name = "meeting_reminders", nullable = false)
	private boolean meetingReminders = true;

	@Column(name = "meeting_changes", nullable = false)
	private boolean meetingChanges = true;

	@Column(name = "new_tasks", nullable = false)
	private boolean newTasks = true;

	@Column(name = "task_deadlines", nullable = false)
	private boolean taskDeadlines;

	@Column(name = "startup_activity", nullable = false)
	private boolean startupActivity = true;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public static NotificationPreference defaultsFor(UUID userId) {
		NotificationPreference preference = new NotificationPreference();
		preference.userId = userId;
		return preference;
	}

	// GENERAL covers account and request decisions, and BADGE badge decisions; neither is ever muted.
	public boolean allows(NotificationCategory category) {
		return switch (category) {
			case GENERAL -> true;
			case MESSAGE -> messages;
			case MEETING_REMINDER -> meetingReminders;
			case MEETING_CHANGE -> meetingChanges;
			case TASK_ASSIGNED -> newTasks;
			case TASK_DEADLINE -> taskDeadlines;
			case STARTUP_ACTIVITY -> startupActivity;
			case BADGE -> true;
		};
	}
}
