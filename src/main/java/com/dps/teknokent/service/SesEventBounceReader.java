package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.dps.teknokent.config.EmailCenterSettings;
import com.dps.teknokent.domain.EmailDeliveryEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the events a configuration set publishes to SQS (through SNS with raw delivery). */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.mail.center", name = "bounce-source", havingValue = "ses-events")
class SesEventBounceReader implements EmailBounceReader {

	private static final Duration WRITTEN_DOWN_WITHIN = Duration.ofMinutes(10);
	private static final int BATCH = 10;
	private static final int MAX_BATCHES = 50;

	private final SqsClient sqsClient;
	private final EmailDeliveryEventService eventService;
	private final EmailCenterSettings settings;
	private final JsonMapper jsonMapper;

	@Override
	public void read() {
		if (settings.getEventsQueueUrl().isEmpty()) {
			return;
		}
		for (int batch = 0; batch < MAX_BATCHES; batch++) {
			List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
					.queueUrl(settings.getEventsQueueUrl())
					.maxNumberOfMessages(BATCH)
					.build()).messages();
			if (messages.isEmpty()) {
				return;
			}
			messages.forEach(this::readOne);
		}
	}

	/** A message that cannot be used stays in the queue, which moves it to its dead-letter queue. */
	private void readOne(Message message) {
		try {
			JsonNode event = jsonMapper.readTree(message.body());
			EmailDeliveryEvent kind = kindOf(event);
			String messageId = event.path("mail").path("messageId").asString("");
			// Unknown to the log: our own mail whose sending is being written down right now, so it comes back.
			if (kind != null && !eventService.apply(messageId, kind) && isRecent(event)) {
				return;
			}
		} catch (JacksonException unreadable) {
			log.warn("An SES event could not be read: {}", unreadable.getClass().getSimpleName());
			return;
		} catch (DataAccessException later) {
			log.warn("An SES event could not be written down yet: {}", later.getClass().getSimpleName());
			return;
		}
		sqsClient.deleteMessage(DeleteMessageRequest.builder()
				.queueUrl(settings.getEventsQueueUrl())
				.receiptHandle(message.receiptHandle())
				.build());
	}

	/** Later than this, a mail the log does not know is not the log's (a test mail, for example). */
	private static boolean isRecent(JsonNode event) {
		try {
			return Instant.parse(event.path("mail").path("timestamp").asString(""))
					.isAfter(Instant.now().minus(WRITTEN_DOWN_WITHIN));
		} catch (DateTimeParseException unknown) {
			return true;
		}
	}

	private static @Nullable EmailDeliveryEvent kindOf(JsonNode event) {
		return switch (event.path("eventType").asString("")) {
		case "Delivery" -> EmailDeliveryEvent.DELIVERED;
		case "Bounce" -> "Permanent".equals(event.path("bounce").path("bounceType").asString(""))
				? EmailDeliveryEvent.HARD_BOUNCE
				: EmailDeliveryEvent.SOFT_BOUNCE;
		case "Complaint" -> EmailDeliveryEvent.COMPLAINT;
		case "Reject" -> EmailDeliveryEvent.REJECTED;
		default -> null;
		};
	}
}
