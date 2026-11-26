package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

class MailServiceTest {

	private final SesV2Client sesClient = mock(SesV2Client.class);

	@Test
	void sendsFromTheConfiguredAddress() {
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr",
				Runnable::run);

		mailService.sendAfterCommit("mentor@tekno.com", "Yeni mentorluk talebi", "Merhaba");

		ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
		verify(sesClient).sendEmail(captor.capture());
		SendEmailRequest sent = captor.getValue();
		assertThat(sent.fromEmailAddress()).isEqualTo("noreply@menton.com.tr");
		assertThat(sent.destination().toAddresses()).containsExactly("mentor@tekno.com");
		assertThat(sent.content().simple().subject().data()).isEqualTo("Yeni mentorluk talebi");
		assertThat(sent.content().simple().body().text().data()).isEqualTo("Merhaba");
	}

	@Test
	void sendsAfterTheCommitOnAnotherThreadSoTheConnectionIsReleasedFirst() {
		List<Runnable> queued = new ArrayList<>();
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr",
				queued::add);
		TransactionSynchronizationManager.initSynchronization();
		try {
			mailService.sendAfterCommit("mentor@tekno.com", "Konu", "Metin");
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
		assertThat(queued).hasSize(1);
		queued.getFirst().run();
		verify(sesClient).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void spacesTheMailsSentAfterACommitSoABurstStaysUnderTheSendingLimit() {
		List<Runnable> queued = new ArrayList<>();
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr",
				queued::add, java.time.Duration.ofMillis(60));
		TransactionSynchronizationManager.initSynchronization();
		try {
			for (int i = 0; i < 3; i++) {
				mailService.sendAfterCommit("mentee" + i + "@tekno.com", "Konu", "Metin");
			}
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		long started = System.nanoTime();
		queued.forEach(Runnable::run);

		verify(sesClient, org.mockito.Mockito.times(3)).sendEmail(any(SendEmailRequest.class));
		assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(
				java.time.Duration.ofMillis(120));
	}

	@Test
	void stillSendsAfterTheCommitWhenTheExecutorIsShuttingDown() {
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr",
				task -> {
					throw new java.util.concurrent.RejectedExecutionException("closed");
				});
		TransactionSynchronizationManager.initSynchronization();
		try {
			mailService.sendAfterCommit("mentor@tekno.com", "Konu", "Metin");
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(sesClient).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void asksWhetherTheMailIsWantedOnlyAfterTheCommitAndSurvivesAFailedLookup() {
		List<Runnable> queued = new ArrayList<>();
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr",
				queued::add);
		List<String> asked = new ArrayList<>();
		TransactionSynchronizationManager.initSynchronization();
		try {
			mailService.sendAfterCommit("a@tekno.com", "Konu", "Metin", () -> {
				asked.add("refused");
				return false;
			});
			mailService.sendAfterCommit("b@tekno.com", "Konu", "Metin", () -> {
				throw new IllegalStateException("lookup failed");
			});
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
		assertThat(asked).isEmpty();

		assertThatCode(() -> queued.forEach(Runnable::run)).doesNotThrowAnyException();
		assertThat(asked).containsExactly("refused");
		verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void sendsNothingWithoutASenderAddress() {
		MailService mailService = new MailService(sesClient, "", "https://menton.com.tr", Runnable::run);

		mailService.sendAfterCommit("mentor@tekno.com", "Konu", "Metin");

		verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void sendsNothingWithoutARecipient() {
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr",
				Runnable::run);

		mailService.sendAfterCommit(" ", "Konu", "Metin");

		verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void keepsGoingWhenSesRejectsTheEmail() {
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr",
				Runnable::run);
		when(sesClient.sendEmail(any(SendEmailRequest.class)))
				.thenThrow(SesV2Exception.builder().message("Email address is not verified.").build());

		assertThatCode(() -> mailService.sendAfterCommit("mentor@tekno.com", "Konu", "Metin"))
				.doesNotThrowAnyException();
	}

	@Test
	void sendNowSaysWhetherSesAcceptedTheEmail() {
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr",
				Runnable::run);
		assertThat(mailService.sendNow("lead@team.co", "Konu", "Metin")).isTrue();

		when(sesClient.sendEmail(any(SendEmailRequest.class)))
				.thenThrow(SesV2Exception.builder().message("Email address is not verified.").build());
		assertThat(mailService.sendNow("lead@team.co", "Konu", "Metin")).isFalse();
		assertThat(new MailService(sesClient, "", "https://menton.com.tr", Runnable::run).sendNow("lead@team.co",
				"Konu", "Metin"))
				.isFalse();
	}

	@Test
	void buildsLinksWithoutADoubleSlash() {
		MailService mailService = new MailService(sesClient, "", "https://menton.com.tr/", Runnable::run);

		assertThat(mailService.link("/mentor-dashboard")).isEqualTo("https://menton.com.tr/mentor-dashboard");
	}
}
