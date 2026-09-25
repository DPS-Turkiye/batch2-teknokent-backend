package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

	@Mock
	private NotificationRepository notificationRepository;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private NotificationService notificationService;

	@Test
	void listsOnlyTheRecipientsNotificationsNewestFirst() {
		UUID recipientId = UUID.randomUUID();
		Notification notification = notification(recipientId);
		when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId))
				.thenReturn(List.of(notification));

		var result = notificationService.list(recipientId);

		assertThat(result).singleElement().satisfies(item -> {
			assertThat(item.id()).isEqualTo(notification.getId());
			assertThat(item.title()).isEqualTo("Yeni mesaj");
			assertThat(item.readAt()).isNull();
		});
	}

	@Test
	void markReadPersistsTheFirstReadTime() {
		UUID recipientId = UUID.randomUUID();
		Notification notification = notification(recipientId);
		when(notificationRepository.findByIdAndRecipientId(notification.getId(), recipientId))
				.thenReturn(Optional.of(notification));
		when(notificationRepository.save(notification)).thenReturn(notification);

		var result = notificationService.markRead(recipientId, notification.getId());

		assertThat(result.readAt()).isNotNull();
		verify(notificationRepository).save(notification);
	}

	@Test
	void deleteCannotReachAnotherUsersNotification() {
		UUID recipientId = UUID.randomUUID();
		UUID notificationId = UUID.randomUUID();
		when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> notificationService.delete(recipientId, notificationId))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void createTrimsStoredContent() {
		AppUser recipient = new AppUser();
		recipient.setId(UUID.randomUUID());
		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);

		notificationService.create(recipient, "  Başlık  ", "  İçerik  ", " /dashboard ");

		verify(notificationRepository).save(captor.capture());
		assertThat(captor.getValue().getRecipient()).isSameAs(recipient);
		assertThat(captor.getValue().getTitle()).isEqualTo("Başlık");
		assertThat(captor.getValue().getBody()).isEqualTo("İçerik");
		assertThat(captor.getValue().getHref()).isEqualTo("/dashboard");
		assertThat(captor.getValue().getCategory()).isEqualTo(NotificationCategory.GENERAL);
	}

	@Test
	void createAnnouncesTheNotificationForPushWithItsCategory() {
		AppUser recipient = new AppUser();
		recipient.setId(UUID.randomUUID());
		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		ArgumentCaptor<NotificationCreatedEvent> event = ArgumentCaptor.forClass(NotificationCreatedEvent.class);

		notificationService.create(recipient, NotificationCategory.MESSAGE, "Yeni mesaj", "Merhaba", "/mentorships");

		verify(notificationRepository).save(saved.capture());
		verify(eventPublisher).publishEvent(event.capture());
		assertThat(saved.getValue().getCategory()).isEqualTo(NotificationCategory.MESSAGE);
		assertThat(event.getValue().recipientId()).isEqualTo(recipient.getId());
		assertThat(event.getValue().message().notificationId()).isEqualTo(saved.getValue().getId());
		assertThat(event.getValue().message().category()).isEqualTo(NotificationCategory.MESSAGE);
		assertThat(event.getValue().message().href()).isEqualTo("/mentorships");
	}

	@Test
	void unreadCountCountsOnlyTheRecipientsUnreadNotifications() {
		UUID recipientId = UUID.randomUUID();
		when(notificationRepository.countByRecipientIdAndReadAtIsNull(recipientId)).thenReturn(3L);

		assertThat(notificationService.unreadCount(recipientId).unread()).isEqualTo(3L);
	}

	@Test
	void markAllReadStampsOnlyTheRecipientsNotifications() {
		UUID recipientId = UUID.randomUUID();

		notificationService.markAllRead(recipientId);

		verify(notificationRepository).markAllRead(eq(recipientId), any(Instant.class));
	}

	private Notification notification(UUID recipientId) {
		AppUser recipient = new AppUser();
		recipient.setId(recipientId);
		Notification notification = new Notification();
		notification.setId(UUID.randomUUID());
		notification.setRecipient(recipient);
		notification.setTitle("Yeni mesaj");
		notification.setBody("Bir mesajınız var.");
		notification.setHref("/dashboard");
		notification.setCreatedAt(Instant.parse("2026-09-08T12:00:00Z"));
		return notification;
	}
}
