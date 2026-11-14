package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

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

	@Mock
	private PlatformTransactionManager transactionManager;

	private NotificationReminderService reminderService;

	@BeforeEach
	void setUp() {
		reminderService = new NotificationReminderService(meetingRepository, actionItemRepository,
				appUserRepository, notificationService, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void remindsTheMentorAndTheStartupsMenteesOfAMeetingWithinHalfAnHour() {
		Organization organization = organization();
		AppUser mentor = user("Selin Aydın", UserRole.MENTOR);
		AppUser founder = member("Gamze Kozak", UserRole.MENTEE, organization);
		AppUser coordinator = member("Koordinatör", UserRole.COORDINATOR, organization);
		Meeting meeting = meeting(organization, mentor, Instant.parse("2026-09-24T09:25:00Z"));
		dueMeetings(meeting);
		when(appUserRepository.findByOrganizationIdInOrderByFullNameAsc(Set.of(organization.getId())))
				.thenReturn(List.of(founder, coordinator));

		reminderService.sendDueReminders();

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
		dueTasks(task);

		reminderService.sendDueReminders();

		verify(notificationService).create(founder, NotificationCategory.TASK_DEADLINE,
				"Görev teslim tarihi yaklaşıyor", "«Finansal model» görevinin son günü yarın.", "/startup/process");
		assertThat(task.getDeadlineRemindedAt()).isEqualTo(NOW);
	}

	@Test
	void saysTodayForATaskDueToday() {
		AppUser founder = user("Gamze Kozak", UserRole.MENTEE);
		dueTasks(task(founder, TODAY));

		reminderService.sendDueReminders();

		verify(notificationService).create(founder, NotificationCategory.TASK_DEADLINE,
				"Görev teslim tarihi yaklaşıyor", "«Finansal model» görevinin son günü bugün.", "/startup/process");
	}

	@Test
	void writesEachReminderInItsReadersLanguage() {
		AppUser founder = user("Gamze Kozak", UserRole.MENTEE);
		founder.setPreferredLanguage(AppLanguage.EN);
		dueTasks(task(founder, TODAY));

		reminderService.sendDueReminders();

		verify(notificationService).create(founder, NotificationCategory.TASK_DEADLINE,
				"Task deadline coming up", "The task “Finansal model” is due today.", "/startup/process");
	}

	@Test
	void aMeetingWhoseReminderFailsDoesNotStopTheOthers() {
		AppUser brokenMentor = user("Bozuk Mentor", UserRole.MENTOR);
		AppUser mentor = user("Selin Aydın", UserRole.MENTOR);
		Meeting broken = meeting(organization(), brokenMentor, Instant.parse("2026-09-24T09:10:00Z"));
		Meeting fine = meeting(organization(), mentor, Instant.parse("2026-09-24T09:25:00Z"));
		dueMeetings(broken, fine);
		when(notificationService.create(eq(brokenMentor), any(), anyString(), anyString(), anyString()))
				.thenThrow(new IllegalStateException("bad row"));

		reminderService.sendDueReminders();

		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MEETING_REMINDER), anyString(),
				anyString(), eq("/mentor-dashboard/calendar"));
		assertThat(fine.getReminderSentAt()).isEqualTo(NOW);
		assertThat(broken.getReminderSentAt()).isNull();
	}

	@Test
	void readsTheMenteesOfEveryTeamWithAMeetingDueInOneQuery() {
		Organization first = organization();
		Organization second = organization();
		AppUser founder = member("Gamze Kozak", UserRole.MENTEE, first);
		AppUser other = member("Ece Kurt", UserRole.MENTEE, second);
		dueMeetings(meeting(first, null, Instant.parse("2026-09-24T09:10:00Z")),
				meeting(second, null, Instant.parse("2026-09-24T09:25:00Z")));
		when(appUserRepository.findByOrganizationIdInOrderByFullNameAsc(Set.of(first.getId(), second.getId())))
				.thenReturn(List.of(founder, other));

		reminderService.sendDueReminders();

		verify(notificationService).create(founder, NotificationCategory.MEETING_REMINDER, "Görüşmen yaklaşıyor",
				"Mentörünle görüşmen yakında başlıyor · 12:10", "/mentorships?tab=active");
		verify(notificationService).create(other, NotificationCategory.MEETING_REMINDER, "Görüşmen yaklaşıyor",
				"Mentörünle görüşmen yakında başlıyor · 12:25", "/mentorships?tab=active");
		verify(appUserRepository).findByOrganizationIdInOrderByFullNameAsc(anyCollection());
		verify(appUserRepository, never()).findByOrganizationIdOrderByFullNameAsc(any());
	}

	@Test
	void doesNotRemindOfAMeetingCancelledAfterTheListWasRead() {
		AppUser mentor = user("Selin Aydın", UserRole.MENTOR);
		Meeting meeting = meeting(organization(), mentor, Instant.parse("2026-09-24T09:25:00Z"));
		dueMeetings(meeting);
		meeting.setStatus(MeetingStatus.CANCELLED);

		reminderService.sendDueReminders();

		verify(notificationService, never()).create(any(), any(), any(), any(), any());
		assertThat(meeting.getReminderSentAt()).isNull();
	}

	@Test
	void aTaskWhoseReminderFailsDoesNotStopTheOthers() {
		AppUser brokenOwner = user("Bozuk Kişi", UserRole.MENTEE);
		AppUser founder = user("Gamze Kozak", UserRole.MENTEE);
		ActionItem broken = task(brokenOwner, TODAY);
		ActionItem fine = task(founder, TODAY);
		dueTasks(broken, fine);
		when(notificationService.create(eq(brokenOwner), any(), anyString(), anyString(), anyString()))
				.thenThrow(new IllegalStateException("bad row"));

		reminderService.sendDueReminders();

		verify(notificationService).create(founder, NotificationCategory.TASK_DEADLINE,
				"Görev teslim tarihi yaklaşıyor", "«Finansal model» görevinin son günü bugün.", "/startup/process");
		assertThat(fine.getDeadlineRemindedAt()).isEqualTo(NOW);
		assertThat(broken.getDeadlineRemindedAt()).isNull();
	}

	private void dueMeetings(Meeting... meetings) {
		when(meetingRepository.findByStatusAndReminderSentAtIsNullAndScheduledAtBetween(
				MeetingStatus.SCHEDULED, NOW, NOW.plus(NotificationReminderService.MEETING_LEAD)))
				.thenReturn(List.of(meetings));
		for (Meeting meeting : meetings) {
			when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		}
	}

	private void dueTasks(ActionItem... tasks) {
		when(actionItemRepository.findByStatusInAndDeadlineRemindedAtIsNullAndDueDateBetween(
				any(), eq(TODAY), eq(TODAY.plusDays(1))))
				.thenReturn(List.of(tasks));
		for (ActionItem task : tasks) {
			when(actionItemRepository.findById(task.getId())).thenReturn(Optional.of(task));
		}
	}

	private static AppUser member(String name, UserRole role, Organization organization) {
		AppUser user = user(name, role);
		user.setOrganization(organization);
		return user;
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
