package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.TranscriptBotConstants;
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
		Instant from = now.minus(TranscriptBotConstants.DISPATCH_LOOKBACK_HOURS, ChronoUnit.HOURS);
		Instant until = now.plus(TranscriptBotConstants.DISPATCH_LOOKAHEAD_MINUTES, ChronoUnit.MINUTES);
		List<Meeting> due = meetingRepository
				.findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
						MeetingStatus.SCHEDULED, from, until);

		Map<String, List<Meeting>> byLink = new LinkedHashMap<>();
		for (Meeting meeting : due) {
			String link = meeting.getMeetLink();
			if (link == null || link.isBlank()) {
				continue;
			}
			Integer durationMinutes = meeting.getDurationMinutes();
			int duration = durationMinutes == null
					? TranscriptBotConstants.DEFAULT_DURATION_MINUTES
					: durationMinutes;
			Instant endsAt = meeting.getScheduledAt().plus(duration, ChronoUnit.MINUTES);
			if (now.isAfter(endsAt)) {
				continue;
			}
			byLink.computeIfAbsent(link.trim(), ignored -> new ArrayList<>()).add(meeting);
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
				if (isUnrecoverableJoinFailure(error)) {
					for (Meeting meeting : entry.getValue()) {
						meeting.setBotDispatchedAt(dispatchedAt);
						meeting.setStatus(MeetingStatus.COMPLETED);
					}
				}
			}
		}
		return sent;
	}

	static boolean isUnrecoverableJoinFailure(Throwable error) {
		String text = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
		return text.contains("ended")
				|| text.contains("finished")
				|| text.contains("no longer")
				|| text.contains("destroyed");
	}
}
