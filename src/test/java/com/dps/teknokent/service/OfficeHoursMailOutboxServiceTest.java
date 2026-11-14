package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.entity.OfficeHoursMailOutbox;
import com.dps.teknokent.repository.OfficeHoursMailOutboxRepository;

@ExtendWith(MockitoExtension.class)
class OfficeHoursMailOutboxServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-12T20:00:00Z");

	@Mock
	private OfficeHoursMailOutboxRepository outboxRepository;
	@Mock
	private MailService mailService;

	private OfficeHoursMailOutboxService service;

	@BeforeEach
	void setUp() {
		service = new OfficeHoursMailOutboxService(outboxRepository, mailService, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static OfficeHoursMailOutbox queued(int attempts) {
		OfficeHoursMailOutbox mail = new OfficeHoursMailOutbox();
		mail.setId(UUID.randomUUID());
		mail.setToAddress("ayse@metu.edu.tr");
		mail.setSubject("Ofis saati talebiniz kabul edildi · CENG 101");
		mail.setBody("Merhaba");
		mail.setAttempts(attempts);
		mail.setNextAttemptAt(NOW.minusSeconds(30));
		return mail;
	}

	private void due(OfficeHoursMailOutbox... mails) {
		when(outboxRepository.findTop50ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(NOW))
				.thenReturn(List.of(mails));
	}

	private void claimable(OfficeHoursMailOutbox mail) {
		when(outboxRepository.claim(mail.getId(), mail.getNextAttemptAt(),
				NOW.plus(OfficeHoursMailOutboxService.LEASE))).thenReturn(1);
	}

	@Test
	void queuesAMailToBeSentNowAndQueuesNothingWithMailSwitchedOff() {
		when(mailService.isConfigured()).thenReturn(true);
		service.enqueue("ayse@metu.edu.tr", "Konu", "Gövde");

		ArgumentCaptor<OfficeHoursMailOutbox> saved = ArgumentCaptor.forClass(OfficeHoursMailOutbox.class);
		verify(outboxRepository).save(saved.capture());
		assertThat(saved.getValue().getToAddress()).isEqualTo("ayse@metu.edu.tr");
		assertThat(saved.getValue().getNextAttemptAt()).isEqualTo(NOW);
		assertThat(saved.getValue().getAttempts()).isZero();
		// Nothing goes to SES here: the mail waits for the change to commit.
		verify(mailService, never()).sendNow(any(), any(), any());

		when(mailService.isConfigured()).thenReturn(false);
		service.enqueue("ayse@metu.edu.tr", "Konu", "Gövde");
		verify(outboxRepository).save(any());
	}

	@Test
	void aSentMailIsDeletedSoItsAddressAndTopicDoNotStay() {
		OfficeHoursMailOutbox mail = queued(0);
		due(mail);
		claimable(mail);
		when(mailService.sendNow("ayse@metu.edu.tr", mail.getSubject(), "Merhaba")).thenReturn(true);

		assertThat(service.deliverDue(NOW)).isEqualTo(1);

		verify(outboxRepository).deleteSent(mail.getId());
		verify(outboxRepository, never()).retryAt(any(), any());
	}

	@Test
	void aFailedSendWaitsItsBackOffAndTriesAgain() {
		OfficeHoursMailOutbox first = queued(0);
		OfficeHoursMailOutbox third = queued(2);
		due(first, third);
		claimable(first);
		claimable(third);
		when(mailService.sendNow(any(), any(), any())).thenReturn(false);

		assertThat(service.deliverDue(NOW)).isZero();

		verify(outboxRepository).retryAt(first.getId(), NOW.plus(OfficeHoursMailOutboxService.BACKOFF.get(0)));
		verify(outboxRepository).retryAt(third.getId(), NOW.plus(OfficeHoursMailOutboxService.BACKOFF.get(2)));
		verify(outboxRepository, never()).deleteSent(any());
	}

	@Test
	void aMailThatFailsEveryAttemptIsDroppedAfterTheLast() {
		OfficeHoursMailOutbox mail = queued(OfficeHoursMailOutboxService.MAX_ATTEMPTS - 1);
		due(mail);
		claimable(mail);
		when(mailService.sendNow(any(), any(), any())).thenReturn(false);

		service.deliverDue(NOW);

		verify(outboxRepository).deleteSent(mail.getId());
		verify(outboxRepository, never()).retryAt(any(), any());
	}

	@Test
	void aMailAnotherServerTookIsNotSentTwice() {
		OfficeHoursMailOutbox mail = queued(0);
		due(mail);
		when(outboxRepository.claim(any(), any(), any())).thenReturn(0);

		service.deliverDue(NOW);

		verifyNoInteractions(mailService);
		verify(outboxRepository, never()).deleteSent(any());
	}
}
