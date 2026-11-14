package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

import lombok.extern.slf4j.Slf4j;

/**
 * Each reminder is sent and marked in its own transaction, so one row that fails is retried on the next tick
 * while every other reminder still goes out.
 */
@Slf4j
@Service
public class NotificationReminderService {

	static final Duration MEETING_LEAD = Duration.ofMinutes(30);
	private static final Set<ActionItemStatus> OPEN_TASK_STATUSES = Set.of(ActionItemStatus.OPEN,
			ActionItemStatus.IN_PROGRESS);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
			.withZone(MeetingSlotTimes.APP_ZONE);
	private static final String MENTEE_MEETINGS_HREF = "/mentorships?tab=active";
	private static final String MENTOR_MEETINGS_HREF = "/mentor-dashboard/calendar";
	private static final String TASKS_HREF = "/startup/process";

	private final MeetingRepository meetingRepository;
	private final ActionItemRepository actionItemRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;
	private final TransactionTemplate reading;
	private final TransactionTemplate writing;
	private final Clock clock;

	@Autowired
	public NotificationReminderService(MeetingRepository meetingRepository,
			ActionItemRepository actionItemRepository, AppUserRepository appUserRepository,
			NotificationService notificationService, PlatformTransactionManager transactionManager) {
		this(meetingRepository, actionItemRepository, appUserRepository, notificationService, transactionManager,
				Clock.systemUTC());
	}

	NotificationReminderService(MeetingRepository meetingRepository, ActionItemRepository actionItemRepository,
			AppUserRepository appUserRepository, NotificationService notificationService,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.meetingRepository = meetingRepository;
		this.actionItemRepository = actionItemRepository;
		this.appUserRepository = appUserRepository;
		this.notificationService = notificationService;
		this.reading = new TransactionTemplate(transactionManager);
		this.reading.setReadOnly(true);
		this.writing = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** One moment for both, so a meeting and a deadline at the same minute are judged against the same "now". */
	public void sendDueReminders() {
		Instant now = clock.instant();
		remindUpcomingMeetings(now);
		remindTaskDeadlines(now);
	}

	void remindUpcomingMeetings(Instant now) {
		DueMeetings due = reading.execute(status -> dueMeetings(now));
		if (due == null || due.meetingIds().isEmpty()) {
			return;
		}
		for (UUID meetingId : due.meetingIds()) {
			try {
				writing.executeWithoutResult(status -> remindMeeting(meetingId, due.mentees(), now));
			} catch (RuntimeException failure) {
				log.warn("Meeting reminder failed for meeting {}", meetingId, failure);
			}
		}
	}

	/** The mentees of every team with a meeting due, read in one query rather than one per meeting. */
	private record DueMeetings(List<UUID> meetingIds, Map<UUID, List<AppUser>> mentees) {
	}

	private DueMeetings dueMeetings(Instant now) {
		List<Meeting> meetings = meetingRepository.findByStatusAndReminderSentAtIsNullAndScheduledAtBetween(
				MeetingStatus.SCHEDULED, now, now.plus(MEETING_LEAD));
		if (meetings.isEmpty()) {
			return new DueMeetings(List.of(), Map.of());
		}
		Set<UUID> organizationIds = meetings.stream().map(meeting -> meeting.getOrganization().getId())
				.collect(Collectors.toSet());
		Map<UUID, List<AppUser>> mentees = appUserRepository.findByOrganizationIdInOrderByFullNameAsc(organizationIds)
				.stream()
				.filter(user -> user.getOrganization() != null && user.has(UserRole.MENTEE))
				.collect(Collectors.groupingBy(user -> user.getOrganization().getId()));
		return new DueMeetings(meetings.stream().map(Meeting::getId).toList(), mentees);
	}

	private void remindMeeting(UUID meetingId, Map<UUID, List<AppUser>> menteesByTeam, Instant now) {
		Meeting meeting = meetingRepository.findById(meetingId).orElse(null);
		// Cancelled, moved or reminded since the list was read.
		if (meeting == null || meeting.getStatus() != MeetingStatus.SCHEDULED || meeting.getReminderSentAt() != null
				|| meeting.getScheduledAt() == null || meeting.getScheduledAt().isBefore(now)
				|| meeting.getScheduledAt().isAfter(now.plus(MEETING_LEAD))) {
			return;
		}
		String startsAt = TIME.format(meeting.getScheduledAt());
		AppUser mentor = meeting.getMentor();
		if (mentor != null) {
			notificationService.create(
					mentor,
					NotificationCategory.MEETING_REMINDER,
					Texts.get(mentor, "notification.meetingSoon.title"),
					Texts.get(mentor, "notification.meetingSoon.body", meeting.getOrganization().getName(), startsAt),
					MENTOR_MEETINGS_HREF);
		}
		for (AppUser mentee : menteesByTeam.getOrDefault(meeting.getOrganization().getId(), List.of())) {
			notificationService.create(
					mentee,
					NotificationCategory.MEETING_REMINDER,
					Texts.get(mentee, "notification.meetingSoon.title"),
					mentor == null
							? Texts.get(mentee, "notification.meetingSoonNoMentor.body", startsAt)
							: Texts.get(mentee, "notification.meetingSoon.body", mentor.getFullName(), startsAt),
					MENTEE_MEETINGS_HREF);
		}
		meeting.setReminderSentAt(now);
	}

	// Tasks due today or tomorrow, so a missed run the day before still reminds on the day.
	void remindTaskDeadlines(Instant now) {
		LocalDate today = LocalDate.ofInstant(now, MeetingSlotTimes.APP_ZONE);
		List<UUID> taskIds = reading.execute(status -> actionItemRepository
				.findByStatusInAndDeadlineRemindedAtIsNullAndDueDateBetween(OPEN_TASK_STATUSES, today,
						today.plusDays(1))
				.stream().map(ActionItem::getId).toList());
		if (taskIds == null) {
			return;
		}
		for (UUID taskId : taskIds) {
			try {
				writing.executeWithoutResult(status -> remindTask(taskId, today, now));
			} catch (RuntimeException failure) {
				log.warn("Deadline reminder failed for task {}", taskId, failure);
			}
		}
	}

	private void remindTask(UUID taskId, LocalDate today, Instant now) {
		ActionItem task = actionItemRepository.findById(taskId).orElse(null);
		// Done, moved or reminded since the list was read.
		if (task == null || !OPEN_TASK_STATUSES.contains(task.getStatus()) || task.getDeadlineRemindedAt() != null
				|| task.getDueDate() == null || task.getDueDate().isBefore(today)
				|| task.getDueDate().isAfter(today.plusDays(1))) {
			return;
		}
		AppUser owner = task.getOwner();
		String whenKey = task.getDueDate().equals(today)
				? "notification.taskDeadline.today"
				: "notification.taskDeadline.tomorrow";
		notificationService.create(
				owner,
				NotificationCategory.TASK_DEADLINE,
				Texts.get(owner, "notification.taskDeadline.title"),
				Texts.get(owner, "notification.taskDeadline.body", task.getTitle(), Texts.get(owner, whenKey)),
				TASKS_HREF);
		task.setDeadlineRemindedAt(now);
	}
}
