package com.dps.teknokent.service;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "meeting.transcript-bot",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public class MeetingTranscriptBotScheduler {

	private final MeetingTranscriptBotDispatcher dispatcher;

	@Scheduled(fixedDelayString = "${meeting.transcript-bot.interval-ms:30000}")
	public void dispatchDueTranscriptBots() {
		dispatcher.dispatchDueMeetings(Instant.now());
	}
}
