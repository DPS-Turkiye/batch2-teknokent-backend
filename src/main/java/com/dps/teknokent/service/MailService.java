package com.dps.teknokent.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Autowired;
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
	private final long afterCommitGapNanos;
	private final Object pace = new Object();
	private long nextAfterCommitSend = System.nanoTime();

	/**
	 * The mails sent after an action share the SES account (14 a second) with the email center's 10 a second, so
	 * they keep to 4 a second: a program-wide notice then waits a little instead of being throttled and lost.
	 */
	static final Duration AFTER_COMMIT_GAP = Duration.ofMillis(250);

	@Autowired
	public MailService(
			SesV2Client sesClient,
			@Value("${app.mail.from:}") String fromAddress,
			@Value("${app.mail.public-url:https://menton.com.tr}") String publicUrl,
			@Qualifier("mailExecutor") Executor afterCommitExecutor) {
		this(sesClient, fromAddress, publicUrl, afterCommitExecutor, AFTER_COMMIT_GAP);
	}

	MailService(SesV2Client sesClient, String fromAddress, String publicUrl, Executor afterCommitExecutor,
			Duration afterCommitGap) {
		this.sesClient = sesClient;
		this.afterCommitExecutor = afterCommitExecutor;
		this.afterCommitGapNanos = afterCommitGap.toNanos();
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
		sendAfterCommit(to, subject, body, () -> true);
	}

	/** Asks {@code wanted} just before sending, so a lookup that fails cannot roll back the action. */
	public void sendAfterCommit(String to, String subject, String body, BooleanSupplier wanted) {
		if (fromAddress.isEmpty() || to == null || to.isBlank()) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			sendIfWanted(to, subject, body, wanted);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				// afterCommit runs before the transaction hands its connection back, so sending here would hold
				// that connection for the whole SES round trip.
				try {
					afterCommitExecutor.execute(() -> {
						waitForTurn();
						sendIfWanted(to, subject, body, wanted);
					});
				} catch (RejectedExecutionException shuttingDown) {
					// Only while shutting down; this inline send does hold the connection.
					sendIfWanted(to, subject, body, wanted);
				}
			}
		});
	}

	/** Each queued mail takes the next free slot, AFTER_COMMIT_GAP after the one before. */
	private void waitForTurn() {
		long wait;
		synchronized (pace) {
			long now = System.nanoTime();
			long slot = Math.max(now, nextAfterCommitSend);
			nextAfterCommitSend = slot + afterCommitGapNanos;
			wait = slot - now;
		}
		if (wait > 0) {
			try {
				Thread.sleep(Duration.ofNanos(wait));
			} catch (InterruptedException interrupted) {
				// Shutting down: send now rather than drop the mail.
				Thread.currentThread().interrupt();
			}
		}
	}

	private void sendIfWanted(String to, String subject, String body, BooleanSupplier wanted) {
		try {
			if (wanted.getAsBoolean()) {
				send(to, subject, body);
			}
		} catch (RuntimeException error) {
			log.warn("Email to {} failed: {}", to, error.getMessage());
		}
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
