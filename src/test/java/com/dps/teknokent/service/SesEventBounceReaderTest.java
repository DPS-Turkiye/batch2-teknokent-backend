package com.dps.teknokent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.dps.teknokent.config.EmailCenterSettings;
import com.dps.teknokent.domain.EmailDeliveryEvent;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.json.JsonMapper;

class SesEventBounceReaderTest {

	private final SqsClient sqsClient = mock(SqsClient.class);
	private final EmailDeliveryEventService eventService = mock(EmailDeliveryEventService.class);

	@ParameterizedTest
	@CsvSource({ "Delivery,Permanent,DELIVERED", "Bounce,Permanent,HARD_BOUNCE", "Bounce,Transient,SOFT_BOUNCE",
			"Complaint,Permanent,COMPLAINT", "Reject,Permanent,REJECTED" })
	void writesDownWhatSesReportsAndRemovesItFromTheQueue(String type, String bounceType, EmailDeliveryEvent kind) {
		when(eventService.apply("m-1", kind)).thenReturn(true);
		queue("{\"eventType\":\"" + type + "\",\"mail\":{\"messageId\":\"m-1\"},\"bounce\":{\"bounceType\":\""
				+ bounceType + "\"}}");

		reader("https://queue").read();

		verify(eventService).apply("m-1", kind);
		verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
	}

	@Test
	void aReportAboutAMailNotWrittenDownYetComesBack() {
		queue("{\"eventType\":\"Bounce\",\"mail\":{\"messageId\":\"m-2\"}}");
		reader("https://queue").read();
		verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
	}

	@Test
	void anOldReportAboutAMailTheLogDoesNotKnowIsDropped() {
		queue("{\"eventType\":\"Bounce\",\"mail\":{\"messageId\":\"m-3\",\"timestamp\":\"2026-01-01T00:00:00Z\"}}");
		reader("https://queue").read();
		verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
	}

	@Test
	void anUnreadableMessageStaysAndAnUninterestingOneGoes() {
		queue("not json");
		reader("https://queue").read();
		verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));

		queue("{\"eventType\":\"Send\"}");
		reader("https://queue").read();
		verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
		verifyNoInteractions(eventService);
	}

	@Test
	void withoutAQueueNothingIsRead() {
		reader("").read();
		verifyNoInteractions(sqsClient);
	}

	private void queue(String body) {
		when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.thenReturn(ReceiveMessageResponse.builder()
						.messages(Message.builder().body(body).receiptHandle("r").build()).build())
				.thenReturn(ReceiveMessageResponse.builder().build());
	}

	private SesEventBounceReader reader(String queueUrl) {
		return new SesEventBounceReader(sqsClient, eventService, new EmailCenterSettings(true, "", "", "", 10, 1000,
				"set", "ses-events", queueUrl, 300, 120), JsonMapper.builder().build());
	}
}
