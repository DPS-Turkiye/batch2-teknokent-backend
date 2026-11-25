package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;

@ExtendWith(MockitoExtension.class)
class MatchProposalMailServiceTest {

	// 25 Nov 2026, 10:00 in Turkey.
	private static final Instant TEN = Instant.parse("2026-11-25T07:00:00Z");

	@Mock
	private MailService mailService;
	@Mock
	private NotificationPreferenceService preferences;

	private final AppUser reader = reader();
	private MatchProposalMailService service;

	@BeforeEach
	void setUp() {
		service = new MatchProposalMailService(new NotificationMailer(mailService, preferences), true);
		lenient().when(mailService.link(anyString()))
				.thenAnswer(invocation -> "https://menton.com.tr" + invocation.getArgument(0));
	}

	@Test
	void writesTheProposalInTurkishWithTurkeyTimeAndTheLink() {
		sendProposal();

		verifyMailed("menton: görüşme önerisi yanıtınızı bekliyor", """
				Merhaba Ayşe Demir,

				Koordinatör bir görüşme saati önerdi:

				Ayşe Demir ↔ Lumen
				25 Kasım Çarşamba · 10:00 (Türkiye saati)

				Görüşme, mentor ve takım onayladığında kesinleşir. Onaylamak veya reddetmek için: \
				https://menton.com.tr/mentor-dashboard?web=1

				Bu e-postaları almak istemiyorsanız menton'da Bildirim tercihleri'nden "Görüşme değişiklikleri"ni \
				kapatabilirsiniz.

				menton""");
	}

	@Test
	void writesInTheReadersLanguage() {
		reader.setPreferredLanguage(AppLanguage.EN);

		service.send(reader, NotificationCategory.GENERAL, "mail.matchedWithTeam", "/mentor-dashboard",
				"METU Stars 2026", "Lumen");

		verifyMailed("menton: new mentorship match", """
				Hello Ayşe Demir,

				You were matched with the Lumen team in the METU Stars 2026 program.

				To see it on menton: https://menton.com.tr/mentor-dashboard

				menton""");
	}

	@Test
	void namesWhoSuggestedTheNewTime() {
		service.send(reader, NotificationCategory.MEETING_CHANGE, "mail.newTimeProposed", "/mentor-dashboard?web=1",
				"Ayşe Demir ↔ Lumen", TEN, "Deniz Ak");

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mailService).sendAfterCommit(anyString(), anyString(), body.capture(), any());
		assertThat(body.getValue()).contains("Deniz Ak görüşmeniz için yeni bir saat önerdi:");
	}

	@Test
	void sendsNothingWhenSwitchedOff() {
		service = new MatchProposalMailService(new NotificationMailer(mailService, preferences), false);

		sendProposal();

		verifyNoInteractions(mailService, preferences);
	}

	@Test
	void isOffUnlessTheDeploymentTurnsItOn() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withBean(MailService.class, () -> mailService)
				.withBean(NotificationPreferenceService.class, () -> preferences)
				.withBean(NotificationMailer.class)
				.withBean(MatchProposalMailService.class);

		runner.run(context -> context.getBean(MatchProposalMailService.class).send(reader,
				NotificationCategory.GENERAL, "mail.matchedWithTeam", "/mentor-dashboard", "METU Stars", "Lumen"));
		verifyNoInteractions(mailService);

		runner.withPropertyValues("app.mail.match-proposal.enabled=true")
				.run(context -> context.getBean(MatchProposalMailService.class).send(reader,
						NotificationCategory.GENERAL, "mail.matchedWithTeam", "/mentor-dashboard", "METU Stars",
						"Lumen"));
		verify(mailService).sendAfterCommit(anyString(), anyString(), anyString(), any());
	}

	@Test
	void asksTheReadersMeetingChangePreferenceOnlyWhenTheMailIsAboutToGo() {
		when(preferences.allowsEmail(reader.getId(), NotificationCategory.MEETING_CHANGE)).thenReturn(false);

		sendProposal();

		ArgumentCaptor<BooleanSupplier> wanted = ArgumentCaptor.forClass(BooleanSupplier.class);
		verify(mailService).sendAfterCommit(anyString(), anyString(), anyString(), wanted.capture());
		verifyNoInteractions(preferences);
		assertThat(wanted.getValue().getAsBoolean()).isFalse();
	}

	@Test
	void writesOnlyToApprovedAccounts() {
		for (UserAccountStatus status : UserAccountStatus.values()) {
			if (status == UserAccountStatus.APPROVED) {
				continue;
			}
			reader.setStatus(status);
			sendProposal();
		}

		verifyNoInteractions(mailService);
	}

	@Test
	void aFailureWhilePreparingTheMailNeverReachesTheAction() {
		doThrow(new IllegalStateException("broken")).when(mailService).sendAfterCommit(any(), any(), any(), any());

		assertThatCode(this::sendProposal).doesNotThrowAnyException();
	}

	private void verifyMailed(String subject, String body) {
		verify(mailService).sendAfterCommit(eq("ayse@tekno.test"), eq(subject), eq(body), any());
	}

	private void sendProposal() {
		service.send(reader, NotificationCategory.MEETING_CHANGE, "mail.proposalFromCoordinator",
				"/mentor-dashboard?web=1", "Ayşe Demir ↔ Lumen", TEN);
	}

	private static AppUser reader() {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName("Ayşe Demir");
		user.setEmail("ayse@tekno.test");
		return user;
	}
}
