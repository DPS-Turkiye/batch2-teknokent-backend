package com.dps.teknokent.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.dps.teknokent.config.EmailCenterSettings;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/** Hands one email-center mail to SES and says what SES answered. Never logs an address or a text. */
@Component
class EmailCenterMailer {

	private static final int TOO_MANY_REQUESTS = 429;
	private static final int SERVER_ERROR = 500;
	private static final String SENDER_NAME = "Menton";

	private final SesV2Client sesClient;
	private final EmailCenterSettings settings;

	EmailCenterMailer(@Qualifier("emailCenterSesClient") SesV2Client sesClient, EmailCenterSettings settings) {
		this.sesClient = sesClient;
		this.settings = settings;
	}

	/** {@code unsubscribeUrl} is the one-click address for mail programs; null on a test mail. */
	record Mail(
			String to,
			String replyTo,
			String subject,
			String html,
			String text,
			@Nullable String unsubscribeUrl) {
	}

	enum Result {
		ACCEPTED,
		/** SES did not take it and may later. */
		RETRY,
		/** SES refused it, or it is unknown whether SES took it. */
		FAILED
	}

	record Outcome(Result result, @Nullable String messageId, @Nullable String errorCode) {
	}

	Outcome send(Mail mail) {
		if (!settings.canSend() || !settings.mayWriteTo(mail.to())) {
			return new Outcome(Result.FAILED, null, "NOT_ALLOWED");
		}
		try {
			return new Outcome(Result.ACCEPTED, sesClient.sendEmail(request(mail)).messageId(), null);
		} catch (AwsServiceException refused) {
			boolean later = refused.statusCode() == TOO_MANY_REQUESTS || refused.statusCode() >= SERVER_ERROR
					|| refused.isThrottlingException();
			String code = refused.awsErrorDetails() == null ? null : refused.awsErrorDetails().errorCode();
			return new Outcome(later ? Result.RETRY : Result.FAILED, null, code == null ? "SES_ERROR" : code);
		} catch (SdkException unknown) {
			return new Outcome(Result.FAILED, null, "NO_ANSWER");
		}
	}

	private SendEmailRequest request(Mail mail) {
		List<MessageHeader> headers = new ArrayList<>();
		if (mail.unsubscribeUrl() != null) {
			headers.add(header("List-Unsubscribe", "<" + mail.unsubscribeUrl() + ">"));
			headers.add(header("List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
		}
		return SendEmailRequest.builder()
				.fromEmailAddress(SENDER_NAME + " <" + settings.getFrom() + ">")
				.replyToAddresses(mail.replyTo())
				.destination(Destination.builder().toAddresses(mail.to()).build())
				.configurationSetName(settings.getConfigurationSet().isEmpty() ? null : settings.getConfigurationSet())
				.content(EmailContent.builder()
						.simple(Message.builder()
								.subject(utf8(mail.subject()))
								.body(Body.builder().html(utf8(mail.html())).text(utf8(mail.text())).build())
								.headers(headers)
								.build())
						.build())
				.build();
	}

	private static MessageHeader header(String name, String value) {
		return MessageHeader.builder().name(name).value(value).build();
	}

	private static Content utf8(String value) {
		return Content.builder().data(value).charset(StandardCharsets.UTF_8.name()).build();
	}
}
