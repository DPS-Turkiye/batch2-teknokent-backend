package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;

/**
 * Tells mentors about messages they have not read and requests they have not answered, and coordinators about
 * requests that stay unanswered. Each person's follow-up is claimed and sent in its own transaction: the rows are
 * marked by an update that only one run can win, so nobody is told twice and one failure never stops the rest.
 */
@Slf4j
@Service
public class RequestFollowUpService {

	/** A message read in the app within this time sends no mail. */
	static final Duration UNREAD_MESSAGE_DELAY = Duration.ofMinutes(15);
	/** Older messages are never mailed, so turning the mails on does not send yesterday's. */
	static final Duration UNREAD_MESSAGE_WINDOW = Duration.ofHours(24);
	static final Duration REMIND_MENTOR_AFTER = Duration.ofDays(2);
	static final Duration ALERT_COORDINATORS_AFTER = Duration.ofDays(3);
	/** Requests older than this are left alone. */
	static final Duration FOLLOW_UP_WINDOW = Duration.ofDays(30);
	// Reminders are not urgent: they go out in working hours.
	private static final LocalTime DAY_STARTS = LocalTime.of(9, 0);
	private static final LocalTime DAY_ENDS = LocalTime.of(18, 0);

	static final Set<MentorshipRequestStatus> OPEN_CONVERSATIONS = Set.of(
			MentorshipRequestStatus.PENDING,
			MentorshipRequestStatus.MORE_INFO_REQUESTED,
			MentorshipRequestStatus.COUNTER_PROPOSED,
			MentorshipRequestStatus.ACCEPTED);

	private final MentorshipRequestRepository requestRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;
	private final ActivityMailService activityMailService;
	private final TransactionTemplate reading;
	private final TransactionTemplate writing;
	private final Clock clock;

	@Autowired
	public RequestFollowUpService(
			MentorshipRequestRepository requestRepository,
			ProgramCoordinatorRepository programCoordinatorRepository,
			AppUserRepository appUserRepository,
			NotificationService notificationService,
			ActivityMailService activityMailService,
			PlatformTransactionManager transactionManager) {
		this(requestRepository, programCoordinatorRepository, appUserRepository, notificationService,
				activityMailService, transactionManager, Clock.systemUTC());
	}

	RequestFollowUpService(
			MentorshipRequestRepository requestRepository,
			ProgramCoordinatorRepository programCoordinatorRepository,
			AppUserRepository appUserRepository,
			NotificationService notificationService,
			ActivityMailService activityMailService,
			PlatformTransactionManager transactionManager,
			Clock clock) {
		this.requestRepository = requestRepository;
		this.programCoordinatorRepository = programCoordinatorRepository;
		this.appUserRepository = appUserRepository;
		this.notificationService = notificationService;
		this.activityMailService = activityMailService;
		this.reading = new TransactionTemplate(transactionManager);
		this.reading.setReadOnly(true);
		this.writing = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	public void followUp() {
		// The database keeps microseconds; a finer time would not equal what it gives back.
		Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
		mailUnreadMessages(now);
		LocalTime here = now.atZone(MeetingSlotTimes.APP_ZONE).toLocalTime();
		if (!here.isBefore(DAY_STARTS) && here.isBefore(DAY_ENDS)) {
			remindMentors(now);
			alertCoordinators(now);
		}
	}

	void mailUnreadMessages(Instant now) {
		Instant since = now.minus(UNREAD_MESSAGE_WINDOW);
		Instant until = now.minus(UNREAD_MESSAGE_DELAY);
		List<UUID> requestIds = reading.execute(status -> requestRepository.findIdsWithMessagesUnreadByMentor(
				OPEN_CONVERSATIONS, since, until));
		for (UUID requestId : orEmpty(requestIds)) {
			attempt("Unread message mail", requestId, () -> mailUnreadMessage(requestId, since, until));
		}
	}

	private void mailUnreadMessage(UUID requestId, Instant since, Instant until) {
		// Marked up to "until", not "now": a message written in the last quarter of an hour is still mailed later.
		if (requestRepository.claimUnreadMessageMail(requestId, since, until, until) == 0) {
			return;
		}
		requestRepository.findById(requestId).ifPresent(request -> activityMailService.send(
				request.getMentor(),
				NotificationCategory.MESSAGE,
				"mail.unreadMessage",
				"/mentor-dashboard/messages",
				request.getRequester().getFullName()));
	}

	void remindMentors(Instant now) {
		Map<UUID, List<UUID>> waiting = waiting(now, REMIND_MENTOR_AFTER, false,
				request -> request.getMentor().getId());
		waiting.forEach((mentorId, requestIds) -> attempt("Answer reminder", mentorId,
				() -> remindMentor(mentorId, requestIds, now)));
	}

	private void remindMentor(UUID mentorId, List<UUID> requestIds, Instant now) {
		int count = requestRepository.claimAnswerReminder(requestIds, now);
		AppUser mentor = count == 0 ? null : appUserRepository.findById(mentorId).orElse(null);
		if (mentor == null || mentor.getStatus() != UserAccountStatus.APPROVED) {
			return;
		}
		notificationService.create(
				mentor,
				Texts.get(mentor, "notification.requestsWaiting.title"),
				Texts.get(mentor, "notification.requestsWaiting.body", count),
				"/mentor-dashboard");
		activityMailService.send(mentor, NotificationCategory.GENERAL, "mail.requestsWaiting", "/mentor-dashboard",
				count);
	}

	void alertCoordinators(Instant now) {
		Map<UUID, List<UUID>> waiting = waiting(now, ALERT_COORDINATORS_AFTER, true,
				request -> request.getProgram() == null ? null : request.getProgram().getId());
		waiting.forEach((programId, requestIds) -> attempt("Coordinator alert", programId,
				() -> alertCoordinators(requestIds, now)));
	}

	private void alertCoordinators(List<UUID> requestIds, Instant now) {
		if (requestRepository.claimCoordinatorAlert(requestIds, now) == 0) {
			return;
		}
		List<MentorshipRequest> requests = requestRepository.findAllById(requestIds).stream()
				.filter(request -> request.getStatus() == MentorshipRequestStatus.PENDING
						&& now.equals(request.getCoordinatorAlertedAt()))
				.toList();
		if (requests.isEmpty()) {
			return;
		}
		Program program = requests.getFirst().getProgram();
		String mentors = requests.stream()
				.collect(Collectors.groupingBy(request -> request.getMentor().getFullName(), TreeMap::new,
						Collectors.counting()))
				.entrySet().stream()
				.map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
				.collect(Collectors.joining(", "));
		String href = "/coordinator/programs/" + program.getId();
		for (AppUser runner : ProgramCoordination.runners(program, programCoordinatorRepository, appUserRepository)) {
			notificationService.create(
					runner,
					Texts.get(runner, "notification.requestsUnanswered.title", program.getName()),
					Texts.get(runner, "notification.requestsUnanswered.body", requests.size(), mentors),
					href);
			activityMailService.send(runner, NotificationCategory.GENERAL, "mail.requestsUnanswered", href,
					program.getName(), requests.size(), mentors);
		}
	}

	/** The unanswered requests that are due, grouped; a request without a group is left out. */
	private Map<UUID, List<UUID>> waiting(Instant now, Duration age, boolean forCoordinators,
			Function<MentorshipRequest, UUID> group) {
		Instant oldest = now.minus(FOLLOW_UP_WINDOW);
		Instant latest = now.minus(age);
		Map<UUID, List<UUID>> found = reading.execute(status -> (forCoordinators
				? requestRepository.findUnansweredForCoordinators(oldest, latest)
				: requestRepository.findUnansweredForMentors(oldest, latest)).stream()
				.filter(request -> group.apply(request) != null)
				.collect(Collectors.groupingBy(group,
						Collectors.mapping(MentorshipRequest::getId, Collectors.toList()))));
		return found == null ? Map.of() : found;
	}

	private void attempt(String what, UUID id, Runnable action) {
		try {
			writing.executeWithoutResult(status -> action.run());
		} catch (RuntimeException failure) {
			log.warn("{} failed for {}", what, id, failure);
		}
	}

	private static List<UUID> orEmpty(List<UUID> ids) {
		return ids == null ? List.of() : ids;
	}
}
