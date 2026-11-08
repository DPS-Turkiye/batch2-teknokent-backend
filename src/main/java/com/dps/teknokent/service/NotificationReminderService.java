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
import com.dps.teknokent.util.Texts;

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
						Texts.get(meeting.getMentor(), "notification.meetingSoon.title"),
						Texts.get(meeting.getMentor(), "notification.meetingSoon.body",
								meeting.getOrganization().getName(), startsAt),
						MENTOR_MEETINGS_HREF);
			}
			organizationMentees(meeting).forEach(mentee -> notificationService.create(
					mentee,
					NotificationCategory.MEETING_REMINDER,
					Texts.get(mentee, "notification.meetingSoon.title"),
					meeting.getMentor() == null
							? Texts.get(mentee, "notification.meetingSoonNoMentor.body", startsAt)
							: Texts.get(mentee, "notification.meetingSoon.body", meeting.getMentor().getFullName(), startsAt),
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
			String whenKey = task.getDueDate().equals(today)
					? "notification.taskDeadline.today"
					: "notification.taskDeadline.tomorrow";
			notificationService.create(
					task.getOwner(),
					NotificationCategory.TASK_DEADLINE,
					Texts.get(task.getOwner(), "notification.taskDeadline.title"),
					Texts.get(task.getOwner(), "notification.taskDeadline.body", task.getTitle(),
							Texts.get(task.getOwner(), whenKey)),
					TASKS_HREF);
			task.setDeadlineRemindedAt(now);
		}
	}

	private List<AppUser> organizationMentees(Meeting meeting) {
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(meeting.getOrganization().getId()).stream()
				.filter(user -> user.has(UserRole.MENTEE))
				.toList();
	}
}
