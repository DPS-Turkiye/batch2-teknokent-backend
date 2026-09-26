package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

class MailServiceTest {

	private final SesV2Client sesClient = mock(SesV2Client.class);

	@Test
	void sendsFromTheConfiguredAddress() {
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr");

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
	void sendsNothingWithoutASenderAddress() {
		MailService mailService = new MailService(sesClient, "", "https://menton.com.tr");

		mailService.sendAfterCommit("mentor@tekno.com", "Konu", "Metin");

		verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void sendsNothingWithoutARecipient() {
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr");

		mailService.sendAfterCommit(" ", "Konu", "Metin");

		verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void keepsGoingWhenSesRejectsTheEmail() {
		MailService mailService = new MailService(sesClient, "noreply@menton.com.tr", "https://menton.com.tr");
		when(sesClient.sendEmail(any(SendEmailRequest.class)))
				.thenThrow(SesV2Exception.builder().message("Email address is not verified.").build());

		assertThatCode(() -> mailService.sendAfterCommit("mentor@tekno.com", "Konu", "Metin"))
				.doesNotThrowAnyException();
	}

	@Test
	void buildsLinksWithoutADoubleSlash() {
		MailService mailService = new MailService(sesClient, "", "https://menton.com.tr/");

		assertThat(mailService.link("/mentor-dashboard")).isEqualTo("https://menton.com.tr/mentor-dashboard");
	}
}
