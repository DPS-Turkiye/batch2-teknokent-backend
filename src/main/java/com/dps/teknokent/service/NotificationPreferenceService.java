package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.dto.requests.UpdateNotificationPreferencesRequest;
import com.dps.teknokent.dto.responses.NotificationPreferencesResponse;
import com.dps.teknokent.entity.NotificationPreference;
import com.dps.teknokent.repository.NotificationPreferenceRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

	private final NotificationPreferenceRepository notificationPreferenceRepository;

	@Transactional(readOnly = true)
	public NotificationPreferencesResponse get(UUID userId) {
		return NotificationPreferencesResponse.fromEntity(find(userId));
	}

	@Transactional
	public NotificationPreferencesResponse update(UUID userId, UpdateNotificationPreferencesRequest request) {
		NotificationPreference preference = find(userId);
		preference.setMessages(request.messages());
		preference.setMeetingReminders(request.meetingReminders());
		preference.setMeetingChanges(request.meetingChanges());
		preference.setNewTasks(request.newTasks());
		preference.setTaskDeadlines(request.taskDeadlines());
		preference.setStartupActivity(request.startupActivity());
		return NotificationPreferencesResponse.fromEntity(notificationPreferenceRepository.save(preference));
	}

	@Transactional(readOnly = true)
	public boolean allows(UUID userId, NotificationCategory category) {
		return find(userId).allows(category);
	}

	private NotificationPreference find(UUID userId) {
		return notificationPreferenceRepository.findById(userId)
				.orElseGet(() -> NotificationPreference.defaultsFor(userId));
	}
}
