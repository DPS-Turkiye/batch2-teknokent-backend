package com.dps.teknokent.service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

@Slf4j
@Service
public class MailService {

	private final SesV2Client sesClient;
	private final String fromAddress;
	private final String publicUrl;
	private final Executor afterCommitExecutor;

	public MailService(
			SesV2Client sesClient,
			@Value("${app.mail.from:}") String fromAddress,
			@Value("${app.mail.public-url:https://menton.com.tr}") String publicUrl,
			@Qualifier("mailExecutor") Executor afterCommitExecutor) {
		this.sesClient = sesClient;
		this.afterCommitExecutor = afterCommitExecutor;
		this.fromAddress = fromAddress.trim();
		this.publicUrl = publicUrl.trim().replaceAll("/+$", "");
	}

	public String link(String path) {
		return publicUrl + path;
	}

	public boolean isConfigured() {
		return !fromAddress.isEmpty();
	}

	/** Sends right away and says whether SES accepted it; a bounce reported later is not seen here. */
	public boolean sendNow(String to, String subject, String body) {
		return isConfigured() && to != null && !to.isBlank() && send(to, subject, body);
	}

	public void sendAfterCommit(String to, String subject, String body) {
		if (fromAddress.isEmpty() || to == null || to.isBlank()) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			send(to, subject, body);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				// afterCommit runs before the transaction hands its connection back, so sending here would hold
				// that connection for the whole SES round trip.
				try {
					afterCommitExecutor.execute(() -> {
						try {
							send(to, subject, body);
						} catch (RuntimeException error) {
							log.warn("Email to {} failed: {}", to, error.getMessage());
						}
					});
				} catch (RejectedExecutionException shuttingDown) {
					// Only while shutting down; this inline send does hold the connection.
					send(to, subject, body);
				}
			}
		});
	}

	private boolean send(String to, String subject, String body) {
		try {
			sesClient.sendEmail(SendEmailRequest.builder()
					.fromEmailAddress(fromAddress)
					.destination(Destination.builder().toAddresses(to.trim()).build())
					.content(EmailContent.builder()
							.simple(Message.builder()
									.subject(utf8(subject))
									.body(Body.builder().text(utf8(body)).build())
									.build())
							.build())
					.build());
			return true;
		} catch (SdkException e) {
			log.warn("Email to {} could not be sent: {}", to, e.getMessage());
			return false;
		}
	}

	private static Content utf8(String value) {
		return Content.builder().data(value).charset(StandardCharsets.UTF_8.name()).build();
	}
}
