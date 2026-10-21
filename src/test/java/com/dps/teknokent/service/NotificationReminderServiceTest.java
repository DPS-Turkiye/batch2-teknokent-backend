package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;

@ExtendWith(MockitoExtension.class)
class NotificationReminderServiceTest {

	// 12:00 in Istanbul.
	private static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

	@Mock
	private MeetingRepository meetingRepository;

	@Mock
	private ActionItemRepository actionItemRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private NotificationReminderService reminderService;

	@Test
	void remindsTheMentorAndTheStartupsMenteesOfAMeetingWithinHalfAnHour() {
		Organization organization = organization();
		AppUser mentor = user("Selin Aydın", UserRole.MENTOR);
		AppUser founder = user("Gamze Kozak", UserRole.MENTEE);
		AppUser coordinator = user("Koordinatör", UserRole.COORDINATOR);
		Meeting meeting = meeting(organization, mentor, Instant.parse("2026-09-24T09:25:00Z"));
		when(meetingRepository.findByStatusAndReminderSentAtIsNullAndScheduledAtBetween(
				MeetingStatus.SCHEDULED, NOW, NOW.plus(NotificationReminderService.MEETING_LEAD)))
				.thenReturn(List.of(meeting));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()))
				.thenReturn(List.of(founder, coordinator));

		reminderService.remindUpcomingMeetings(NOW);

		verify(notificationService).create(mentor, NotificationCategory.MEETING_REMINDER, "Görüşmen yaklaşıyor",
				"Döngü ile görüşmen yakında başlıyor · 12:25", "/mentor-dashboard/calendar");
		verify(notificationService).create(founder, NotificationCategory.MEETING_REMINDER, "Görüşmen yaklaşıyor",
				"Selin Aydın ile görüşmen yakında başlıyor · 12:25", "/mentorships?tab=active");
		verify(notificationService, never()).create(eq(coordinator), any(), any(), any(), any());
		assertThat(meeting.getReminderSentAt()).isEqualTo(NOW);
	}

	@Test
	void remindsTheOwnerOfATaskDueTomorrowOnce() {
		AppUser founder = user("Gamze Kozak", UserRole.MENTEE);
		ActionItem task = task(founder, TODAY.plusDays(1));
		when(actionItemRepository.findByStatusInAndDeadlineRemindedAtIsNullAndDueDateBetween(
				any(), eq(TODAY), eq(TODAY.plusDays(1))))
				.thenReturn(List.of(task));

		reminderService.remindTaskDeadlines(NOW);

		verify(notificationService).create(founder, NotificationCategory.TASK_DEADLINE,
				"Görev teslim tarihi yaklaşıyor", "«Finansal model» görevinin son günü yarın.", "/startup/process");
		assertThat(task.getDeadlineRemindedAt()).isEqualTo(NOW);
	}

	@Test
	void saysTodayForATaskDueToday() {
		AppUser founder = user("Gamze Kozak", UserRole.MENTEE);
		when(actionItemRepository.findByStatusInAndDeadlineRemindedAtIsNullAndDueDateBetween(
				any(), eq(TODAY), eq(TODAY.plusDays(1))))
				.thenReturn(List.of(task(founder, TODAY)));

		reminderService.remindTaskDeadlines(NOW);

		verify(notificationService).create(founder, NotificationCategory.TASK_DEADLINE,
				"Görev teslim tarihi yaklaşıyor", "«Finansal model» görevinin son günü bugün.", "/startup/process");
	}

	@Test
	void writesEachReminderInItsReadersLanguage() {
		AppUser founder = user("Gamze Kozak", UserRole.MENTEE);
		founder.setPreferredLanguage(AppLanguage.EN);
		when(actionItemRepository.findByStatusInAndDeadlineRemindedAtIsNullAndDueDateBetween(
				any(), eq(TODAY), eq(TODAY.plusDays(1))))
				.thenReturn(List.of(task(founder, TODAY)));

		reminderService.remindTaskDeadlines(NOW);

		verify(notificationService).create(founder, NotificationCategory.TASK_DEADLINE,
				"Task deadline coming up", "The task “Finansal model” is due today.", "/startup/process");
	}

	private static Organization organization() {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName("Döngü");
		return organization;
	}

	private static AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setRole(role);
		return user;
	}

	private static Meeting meeting(Organization organization, AppUser mentor, Instant scheduledAt) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setOrganization(organization);
		meeting.setMentor(mentor);
		meeting.setScheduledAt(scheduledAt);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		return meeting;
	}

	private static ActionItem task(AppUser owner, LocalDate dueDate) {
		ActionItem task = new ActionItem();
		task.setId(UUID.randomUUID());
		task.setOwner(owner);
		task.setTitle("Finansal model");
		task.setStatus(ActionItemStatus.OPEN);
		task.setDueDate(dueDate);
		return task;
	}
}
