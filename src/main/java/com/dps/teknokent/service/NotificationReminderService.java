package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationReminderService {

	static final Duration MEETING_LEAD = Duration.ofMinutes(30);
	private static final Set<ActionItemStatus> OPEN_TASK_STATUSES =
			Set.of(ActionItemStatus.OPEN, ActionItemStatus.IN_PROGRESS);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
			.withZone(MeetingSlotTimes.APP_ZONE);
	private static final String MENTEE_MEETINGS_HREF = "/mentorships?tab=active";
	private static final String MENTOR_MEETINGS_HREF = "/mentor-dashboard/calendar";
	private static final String TASKS_HREF = "/startup/process";

	private final MeetingRepository meetingRepository;
	private final ActionItemRepository actionItemRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;

	@Transactional
	public void remindUpcomingMeetings(Instant now) {
		List<Meeting> meetings = meetingRepository.findByStatusAndReminderSentAtIsNullAndScheduledAtBetween(
				MeetingStatus.SCHEDULED, now, now.plus(MEETING_LEAD));
		for (Meeting meeting : meetings) {
			String startsAt = TIME.format(meeting.getScheduledAt());
			if (meeting.getMentor() != null) {
				notificationService.create(
						meeting.getMentor(),
						NotificationCategory.MEETING_REMINDER,
						"Görüşmen yaklaşıyor",
						meeting.getOrganization().getName() + " ile görüşmen yakında başlıyor · " + startsAt,
						MENTOR_MEETINGS_HREF);
			}
			String withWhom = meeting.getMentor() == null ? "Mentörünle" : meeting.getMentor().getFullName() + " ile";
			organizationMentees(meeting).forEach(mentee -> notificationService.create(
					mentee,
					NotificationCategory.MEETING_REMINDER,
					"Görüşmen yaklaşıyor",
					withWhom + " görüşmen yakında başlıyor · " + startsAt,
					MENTEE_MEETINGS_HREF));
			meeting.setReminderSentAt(now);
		}
	}

	// Tasks due today or tomorrow, so a missed run the day before still reminds on the day.
	@Transactional
	public void remindTaskDeadlines(Instant now) {
		LocalDate today = LocalDate.ofInstant(now, MeetingSlotTimes.APP_ZONE);
		List<ActionItem> tasks = actionItemRepository.findByStatusInAndDeadlineRemindedAtIsNullAndDueDateBetween(
				OPEN_TASK_STATUSES, today, today.plusDays(1));
		for (ActionItem task : tasks) {
			String when = task.getDueDate().equals(today) ? "bugün" : "yarın";
			notificationService.create(
					task.getOwner(),
					NotificationCategory.TASK_DEADLINE,
					"Görev teslim tarihi yaklaşıyor",
					"«" + task.getTitle() + "» görevinin son günü " + when + ".",
					TASKS_HREF);
			task.setDeadlineRemindedAt(now);
		}
	}

	private List<AppUser> organizationMentees(Meeting meeting) {
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(meeting.getOrganization().getId()).stream()
				.filter(user -> user.getRole() == UserRole.MENTEE)
				.toList();
	}
}
