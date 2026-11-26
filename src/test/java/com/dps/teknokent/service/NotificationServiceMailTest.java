package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceMailTest {

	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private MailService mailService;
	@Mock
	private NotificationPreferenceService preferences;

	private final AppUser reader = reader();

	@BeforeEach
	void setUp() {
		lenient().when(mailService.link(anyString()))
				.thenAnswer(invocation -> "https://menton.com.tr" + invocation.getArgument(0));
	}

	@Test
	void mailsTheNotificationsTitleAndTextWithItsLink() {
		service(true).createAndMail(reader, NotificationCategory.GENERAL, " Mentorluk talebiniz kabul edildi ",
				"Ayşe Demir talebinizi kabul etti.", "/mentor-request/status?request=1");

		verify(mailService).sendAfterCommit(eq("deniz@tekno.test"), eq("menton: Mentorluk talebiniz kabul edildi"),
				eq("""
						Merhaba Deniz Ak,

						Mentorluk talebiniz kabul edildi
						Ayşe Demir talebinizi kabul etti.

						menton'da görmek için: https://menton.com.tr/mentor-request/status?request=1

						Bu e-postaları almak istemiyorsanız menton'da Bildirim tercihleri'nden "E-posta \
						bildirimleri"ni kapatabilirsiniz.

						menton"""),
				any());
	}

	@Test
	void writesTheFrameInTheReadersLanguage() {
		reader.setPreferredLanguage(AppLanguage.EN);

		service(true).createAndMail(reader, "Your meeting is coming up", "Your meeting with Ayşe Demir starts soon",
				"/mentorships?tab=active");

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mailService).sendAfterCommit(eq("deniz@tekno.test"), eq("menton: Your meeting is coming up"),
				body.capture(), any());
		assertThat(body.getValue()).startsWith("Hello Deniz Ak,")
				.contains("To see it on menton: https://menton.com.tr/mentorships?tab=active");
	}

	@Test
	void aNotificationWithoutALinkOrTextStillMailsTheHomePage() {
		service(true).createAndMail(reader, NotificationCategory.GENERAL, "Başvurunuz onaylandı", null, null);

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mailService).sendAfterCommit(anyString(), anyString(), body.capture(), any());
		assertThat(body.getValue()).contains("menton'da görmek için: https://menton.com.tr/")
				.doesNotContain("null");
	}

	@Test
	void asksTheReadersPreferenceForTheNotificationsCategory() {
		when(preferences.allowsEmail(reader.getId(), NotificationCategory.TASK_DEADLINE)).thenReturn(false);

		service(true).createAndMail(reader, NotificationCategory.TASK_DEADLINE, "Görev teslim tarihi yaklaşıyor",
				"Pitch deck · bugün", "/startup/process");

		ArgumentCaptor<BooleanSupplier> wanted = ArgumentCaptor.forClass(BooleanSupplier.class);
		verify(mailService).sendAfterCommit(anyString(), anyString(), anyString(), wanted.capture());
		assertThat(wanted.getValue().getAsBoolean()).isFalse();
	}

	@Test
	void onlyNotifiesWhileTheActivityMailsAreOff() {
		service(false).createAndMail(reader, "Başvurunuz onaylandı", "Hoş geldiniz.", "/dashboard");

		verify(notificationRepository).save(any());
		verifyNoInteractions(mailService, preferences);
	}

	@Test
	void aPlainNotificationIsNeverMailed() {
		service(true).create(reader, NotificationCategory.GENERAL, "Yeni rozet: İlk görüşme", "Tebrikler.",
				"/profile");

		verifyNoInteractions(mailService, preferences);
	}

	private NotificationService service(boolean mailsOn) {
		return new NotificationService(notificationRepository, eventPublisher,
				new ActivityMailService(new NotificationMailer(mailService, preferences), mailsOn));
	}

	private static AppUser reader() {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName("Deniz Ak");
		user.setEmail("deniz@tekno.test");
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}
}
