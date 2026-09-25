package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.dto.requests.UpdateNotificationPreferencesRequest;
import com.dps.teknokent.entity.NotificationPreference;
import com.dps.teknokent.repository.NotificationPreferenceRepository;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

	private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

	@Mock
	private NotificationPreferenceRepository notificationPreferenceRepository;

	@InjectMocks
	private NotificationPreferenceService notificationPreferenceService;

	@Test
	void aUserWithoutSavedChoicesGetsEverythingButTaskDeadlines() {
		when(notificationPreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());

		var preferences = notificationPreferenceService.get(USER_ID);

		assertThat(preferences.messages()).isTrue();
		assertThat(preferences.meetingReminders()).isTrue();
		assertThat(preferences.meetingChanges()).isTrue();
		assertThat(preferences.newTasks()).isTrue();
		assertThat(preferences.taskDeadlines()).isFalse();
		assertThat(preferences.startupActivity()).isTrue();
	}

	@Test
	void updateStoresEveryChoiceForTheUser() {
		when(notificationPreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());
		when(notificationPreferenceRepository.save(any(NotificationPreference.class)))
				.thenAnswer(call -> call.getArgument(0));
		ArgumentCaptor<NotificationPreference> saved = ArgumentCaptor.forClass(NotificationPreference.class);

		var preferences = notificationPreferenceService.update(USER_ID,
				new UpdateNotificationPreferencesRequest(false, true, false, true, true, false));

		verify(notificationPreferenceRepository).save(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(preferences.messages()).isFalse();
		assertThat(preferences.meetingChanges()).isFalse();
		assertThat(preferences.taskDeadlines()).isTrue();
		assertThat(preferences.startupActivity()).isFalse();
	}

	@Test
	void mutedCategoriesAreNotAllowedButGeneralAlwaysIs() {
		NotificationPreference preference = NotificationPreference.defaultsFor(USER_ID);
		preference.setMessages(false);
		when(notificationPreferenceRepository.findById(USER_ID)).thenReturn(Optional.of(preference));

		assertThat(notificationPreferenceService.allows(USER_ID, NotificationCategory.MESSAGE)).isFalse();
		assertThat(notificationPreferenceService.allows(USER_ID, NotificationCategory.TASK_DEADLINE)).isFalse();
		assertThat(notificationPreferenceService.allows(USER_ID, NotificationCategory.MEETING_REMINDER)).isTrue();
		assertThat(notificationPreferenceService.allows(USER_ID, NotificationCategory.GENERAL)).isTrue();
	}
}
