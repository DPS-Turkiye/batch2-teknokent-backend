package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class MeetingTranscriptBotSchedulerTest {

	@Test
	void eachTickSendsBotsToDueMeetingsThenClosesFinishedOnesAsOfTheSameMoment() {
		MeetingTranscriptBotDispatcher dispatcher = mock(MeetingTranscriptBotDispatcher.class);
		MeetingTranscriptBotWatchdog watchdog = mock(MeetingTranscriptBotWatchdog.class);
		Instant before = Instant.now();

		new MeetingTranscriptBotScheduler(dispatcher, watchdog).dispatchDueTranscriptBots();

		ArgumentCaptor<Instant> dispatchedAt = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> closedAt = ArgumentCaptor.forClass(Instant.class);
		InOrder order = inOrder(dispatcher, watchdog);
		order.verify(dispatcher).dispatchDueMeetings(dispatchedAt.capture());
		order.verify(watchdog).closeFinishedMeetings(closedAt.capture());
		assertThat(dispatchedAt.getValue()).isEqualTo(closedAt.getValue()).isBetween(before, Instant.now());
	}
}
