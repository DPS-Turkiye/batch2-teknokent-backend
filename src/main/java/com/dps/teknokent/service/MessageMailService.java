package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.CoordinatorConversation;
import com.dps.teknokent.entity.CoordinatorMessage;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.CoordinatorConversationRepository;
import com.dps.teknokent.repository.CoordinatorMessageRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.extern.slf4j.Slf4j;

/**
 * Mails a person about messages written to them that are still unread a quarter of an hour later: a mentor's messages
 * to the requester, and the messages between a program's coordinators and a mentor or team. The mentor's own unread
 * messages are mailed by {@link RequestFollowUpService}. Each side is mailed once per batch of new messages.
 */
@Slf4j
@Service
public class MessageMailService {

	private final MentorshipRequestRepository requestRepository;
	private final CoordinatorConversationRepository conversationRepository;
	private final CoordinatorMessageRepository messageRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final AppUserRepository appUserRepository;
	private final ActivityMailService activityMailService;
	private final TransactionTemplate reading;
	private final TransactionTemplate writing;
	private final Clock clock;

	@Autowired
	public MessageMailService(MentorshipRequestRepository requestRepository,
			CoordinatorConversationRepository conversationRepository, CoordinatorMessageRepository messageRepository,
			ProgramRepository programRepository, ProgramCoordinatorRepository programCoordinatorRepository,
			AppUserRepository appUserRepository, ActivityMailService activityMailService,
			PlatformTransactionManager transactionManager) {
		this(requestRepository, conversationRepository, messageRepository, programRepository,
				programCoordinatorRepository, appUserRepository, activityMailService, transactionManager,
				Clock.systemUTC());
	}

	MessageMailService(MentorshipRequestRepository requestRepository,
			CoordinatorConversationRepository conversationRepository, CoordinatorMessageRepository messageRepository,
			ProgramRepository programRepository, ProgramCoordinatorRepository programCoordinatorRepository,
			AppUserRepository appUserRepository, ActivityMailService activityMailService,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.requestRepository = requestRepository;
		this.conversationRepository = conversationRepository;
		this.messageRepository = messageRepository;
		this.programRepository = programRepository;
		this.programCoordinatorRepository = programCoordinatorRepository;
		this.appUserRepository = appUserRepository;
		this.activityMailService = activityMailService;
		this.reading = new TransactionTemplate(transactionManager);
		this.reading.setReadOnly(true);
		this.writing = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	public void mailUnreadMessages() {
		// The database keeps microseconds; a finer time would not equal what it gives back.
		Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
		mailRequesters(now);
		mailConversations(now);
	}

	void mailRequesters(Instant now) {
		Instant since = now.minus(RequestFollowUpService.UNREAD_MESSAGE_WINDOW);
		Instant until = now.minus(RequestFollowUpService.UNREAD_MESSAGE_DELAY);
		List<UUID> requestIds = reading.execute(status -> requestRepository.findIdsWithMessagesUnreadByMentee(
				RequestFollowUpService.OPEN_CONVERSATIONS, since, until));
		for (UUID requestId : requestIds == null ? List.<UUID>of() : requestIds) {
			attempt("Mentor message mail", requestId, () -> {
				if (requestRepository.claimUnreadMenteeMessageMail(requestId, since, until, now) == 0) {
					return;
				}
				requestRepository.findById(requestId).ifPresent(request -> activityMailService.send(
						request.getRequester(),
						NotificationCategory.MESSAGE,
						"mail.unreadMessage",
						"/mentor-request/status?request=" + request.getId(),
						request.getMentor().getFullName()));
			});
		}
	}

	void mailConversations(Instant now) {
		Instant since = now.minus(RequestFollowUpService.UNREAD_MESSAGE_WINDOW);
		List<UUID> conversationIds = reading.execute(status -> conversationRepository.findByLastMessageAtAfter(since)
				.stream().map(CoordinatorConversation::getId).toList());
		for (UUID conversationId : conversationIds == null ? List.<UUID>of() : conversationIds) {
			attempt("Coordinator message mail", conversationId, () -> mailConversation(conversationId, since, now));
		}
	}

	private void mailConversation(UUID conversationId, Instant since, Instant now) {
		CoordinatorConversation conversation = conversationRepository.findById(conversationId).orElse(null);
		if (conversation == null) {
			return;
		}
		Instant until = now.minus(RequestFollowUpService.UNREAD_MESSAGE_DELAY);
		List<AppUser> counterpart = counterpart(conversation);
		Set<UUID> counterpartIds = counterpart.stream().map(AppUser::getId).collect(Collectors.toSet());
		List<CoordinatorMessage> due = messageRepository
				.findByConversationIdAndCreatedAtAfterOrderByCreatedAtAsc(conversationId, since).stream()
				.filter(message -> message.getSenderId() != null && !message.getCreatedAt().isAfter(until))
				.toList();

		Optional<CoordinatorMessage> forCoordinators = newestUnread(due.stream()
				.filter(message -> counterpartIds.contains(message.getSenderId())).toList(),
				conversation.getCoordinatorReadAt(), conversation.getCoordinatorMailedAt());
		Optional<CoordinatorMessage> forCounterpart = newestUnread(due.stream()
				.filter(message -> !counterpartIds.contains(message.getSenderId())).toList(),
				conversation.getCounterpartReadAt(), conversation.getCounterpartMailedAt());

		forCoordinators.ifPresent(message -> {
			if (conversationRepository.claimCoordinatorMail(conversationId, message.getCreatedAt(), now) == 1) {
				List<AppUser> coordinators = programRepository.findById(conversation.getProgramId())
						.map(program -> ProgramCoordination.runners(program, programCoordinatorRepository,
								appUserRepository))
						.orElse(List.of());
				mailAll(coordinators, message, "/coordinator/messages?conversation=" + conversationId);
			}
		});
		forCounterpart.ifPresent(message -> {
			if (conversationRepository.claimCounterpartMail(conversationId, message.getCreatedAt(), now) == 1) {
				String path = (conversation.getMentorId() != null ? "/mentor-dashboard/messages" : "/messages")
						+ "?conversation=" + conversationId;
				mailAll(counterpart, message, path);
			}
		});
	}

	/** The mentor of a mentor thread, or every member of the team, as the in-app notification reaches them. */
	private List<AppUser> counterpart(CoordinatorConversation conversation) {
		if (conversation.getMentorId() != null) {
			return appUserRepository.findById(conversation.getMentorId()).map(List::of).orElse(List.of());
		}
		if (conversation.getOrganizationId() == null) {
			return List.of();
		}
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(conversation.getOrganizationId());
	}

	private static Optional<CoordinatorMessage> newestUnread(List<CoordinatorMessage> messages, Instant readAt,
			Instant mailedAt) {
		return messages.stream()
				.filter(message -> readAt == null || message.getCreatedAt().isAfter(readAt))
				.filter(message -> mailedAt == null || message.getCreatedAt().isAfter(mailedAt))
				.reduce((first, second) -> second);
	}

	private void mailAll(List<AppUser> readers, CoordinatorMessage message, String path) {
		String sender = appUserRepository.findById(message.getSenderId()).map(AppUser::getFullName).orElse("");
		for (AppUser reader : readers) {
			if (!reader.getId().equals(message.getSenderId())) {
				activityMailService.send(reader, NotificationCategory.MESSAGE, "mail.unreadMessage", path, sender);
			}
		}
	}

	private void attempt(String what, UUID id, Runnable action) {
		try {
			writing.executeWithoutResult(status -> action.run());
		} catch (RuntimeException failure) {
			log.warn("{} failed for {}", what, id, failure);
		}
	}
}
