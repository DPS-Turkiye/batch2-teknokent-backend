package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingTranscriptBotDispatcher {

	private final MeetingRepository meetingRepository;
	private final AssistantService assistantService;

	@Transactional
	public int dispatchDueMeetings(Instant now) {
		Instant from = now.minus(5, ChronoUnit.MINUTES);
		Instant until = now.plus(2, ChronoUnit.MINUTES);
		List<Meeting> due = meetingRepository
				.findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
						MeetingStatus.SCHEDULED, from, until);

		Map<String, List<Meeting>> byLink = new LinkedHashMap<>();
		for (Meeting meeting : due) {
			String link = meeting.getMeetLink();
			if (link == null || link.isBlank()) continue;
			byLink.computeIfAbsent(link.trim(), ignored -> new java.util.ArrayList<>()).add(meeting);
		}

		int sent = 0;
		Instant dispatchedAt = now;
		for (Map.Entry<String, List<Meeting>> entry : byLink.entrySet()) {
			try {
				assistantService.addBot(new AddBotRequest(entry.getKey()));
				for (Meeting meeting : entry.getValue()) {
					meeting.setBotDispatchedAt(dispatchedAt);
				}
				sent++;
			} catch (RuntimeException error) {
				log.warn("Transcript bot could not join {}: {}", entry.getKey(), error.getMessage());
			}
		}
		return sent;
	}
}
