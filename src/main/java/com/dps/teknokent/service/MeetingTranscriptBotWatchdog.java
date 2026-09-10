package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.TranscriptBotConstants;
import com.dps.teknokent.dto.provider.ProviderBotDetails;
import com.dps.teknokent.dto.requests.GenerateMeetingReportRequest;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingTranscriptBotWatchdog {

	private final MeetingRepository meetingRepository;
	private final MeetingService meetingService;
	private final AssistantService assistantService;
	private final MeetingAssistantShareService shareService;
	private final ConcurrentHashMap<String, Instant> emptySince = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Boolean> sawHumans = new ConcurrentHashMap<>();

	public int closeFinishedMeetings(Instant now) {
		List<Meeting> live = meetingRepository
				.findByTranscribeRequestedTrueAndBotDispatchedAtIsNotNullAndStatus(MeetingStatus.SCHEDULED);
		Map<String, Candidate> candidates = candidatesByNativeId(live, now);
		if (candidates.isEmpty()) {
			return 0;
		}

		Optional<Set<String>> running = runningBotKeys();
		if (running.isEmpty()) {
			return 0;
		}

		int closed = 0;
		for (Candidate candidate : candidates.values()) {
			if (!shouldClose(candidate, running.get(), now)) {
				continue;
			}
			closed += closeAsStopped(candidate);
		}
		return closed;
	}

	private boolean shouldClose(Candidate candidate, Set<String> running, Instant now) {
		String key = AssistantService.botKey(candidate.platform, candidate.nativeMeetingId);
		boolean botStillThere = running.contains(key);
		if (!botStillThere) {
			forget(key);
			return true;
		}

		ProviderBotDetails details = botDetails(candidate);
		if (ProviderMeetingPresence.looksFinished(details == null ? null : details.status())) {
			forget(key);
			return true;
		}

		int humans = ProviderMeetingPresence.humanCount(details);
		if (humans > 0) {
			sawHumans.put(key, Boolean.TRUE);
			emptySince.remove(key);
		} else if (humans == 0 && sawHumans.containsKey(key)) {
			Instant firstEmpty = emptySince.putIfAbsent(key, now);
			Instant emptyFrom = firstEmpty == null ? now : firstEmpty;
			if (!now.isBefore(emptyFrom.plus(TranscriptBotConstants.EMPTY_ROOM))) {
				forget(key);
				return true;
			}
		}

		if (candidate.slotEndsAt != null && now.isAfter(
				candidate.slotEndsAt.plus(TranscriptBotConstants.SLOT_GRACE_MINUTES, ChronoUnit.MINUTES))) {
			forget(key);
			return true;
		}
		return false;
	}

	private int closeAsStopped(Candidate candidate) {
		try {
			assistantService.stopBot(candidate.platform, candidate.nativeMeetingId);
		} catch (RuntimeException error) {
			log.warn("Could not stop leftover bot {}: {}", candidate.nativeMeetingId, error.getMessage());
		}
		List<UUID> stopped = meetingService.markBotStopped(candidate.nativeMeetingId);
		if (stopped.isEmpty()) {
			return 0;
		}
		log.info("Meeting {} ended without a stop request; closing {} row(s)",
				candidate.nativeMeetingId, stopped.size());
		storeTranscriptAndReport(stopped, candidate.platform, candidate.nativeMeetingId);
		return stopped.size();
	}

	private ProviderBotDetails botDetails(Candidate candidate) {
		try {
			return assistantService.botDetails(candidate.platform, candidate.nativeMeetingId);
		} catch (RuntimeException error) {
			log.debug("Could not read bot details for {}: {}", candidate.nativeMeetingId, error.getMessage());
			return null;
		}
	}

	private record Candidate(String nativeMeetingId, String platform, Instant slotEndsAt) {}

	private Map<String, Candidate> candidatesByNativeId(List<Meeting> live, Instant now) {
		Map<String, Candidate> candidates = new LinkedHashMap<>();
		Instant joinGraceCutoff = now.minus(
				TranscriptBotConstants.JOIN_GRACE_MINUTES, ChronoUnit.MINUTES);
		for (Meeting meeting : live) {
			Instant dispatchedAt = meeting.getBotDispatchedAt();
			if (dispatchedAt == null || dispatchedAt.isAfter(joinGraceCutoff)) {
				continue;
			}
			String meetLink = meeting.getMeetLink();
			if (meetLink == null || meetLink.isBlank()) {
				continue;
			}
			try {
				MeetingUrlParser.Parsed parsed = MeetingUrlParser.parse(meetLink);
				Integer durationMinutes = meeting.getDurationMinutes();
				int duration = durationMinutes == null
						? TranscriptBotConstants.DEFAULT_DURATION_MINUTES
						: durationMinutes;
				Instant scheduledAt = meeting.getScheduledAt();
				Instant endsAt = scheduledAt == null
						? null
						: scheduledAt.plus(duration, ChronoUnit.MINUTES);
				Candidate next = new Candidate(parsed.nativeMeetingId(), parsed.platform(), endsAt);
				candidates.merge(parsed.nativeMeetingId(), next, (left, right) -> {
					if (left.slotEndsAt == null) {
						return right;
					}
					if (right.slotEndsAt == null) {
						return left;
					}
					return right.slotEndsAt.isAfter(left.slotEndsAt) ? right : left;
				});
			} catch (RuntimeException unsupportedLink) {
				log.debug("Skipping unsupported meeting link {}", meetLink);
			}
		}
		return candidates;
	}

	private Optional<Set<String>> runningBotKeys() {
		try {
			return assistantService.runningBotKeys();
		} catch (RuntimeException error) {
			log.warn("Could not read running bots from the provider: {}", error.getMessage());
			return Optional.empty();
		}
	}

	private void forget(String key) {
		emptySince.remove(key);
		sawHumans.remove(key);
	}

	private void storeTranscriptAndReport(List<UUID> meetingIds, String platform, String nativeMeetingId) {
		List<TranscriptSegmentResponse> segments = List.of();
		try {
			TranscriptResponse transcript = assistantService.getTranscript(platform, nativeMeetingId);
			segments = transcript == null || transcript.segments() == null
					? List.of()
					: transcript.segments();
			for (UUID meetingId : meetingIds) {
				shareService.storeGeneratedTranscript(meetingId, segments);
			}
		} catch (RuntimeException error) {
			log.warn("Could not store the transcript for {}: {}", nativeMeetingId, error.getMessage());
		}
		if (segments.isEmpty()) {
			return;
		}
		try {
			MeetingReportResponse report = assistantService.generateMeetingReport(
					platform,
					nativeMeetingId,
					new GenerateMeetingReportRequest(null, segments));
			for (UUID meetingId : meetingIds) {
				shareService.storeGeneratedReport(meetingId, report);
			}
		} catch (RuntimeException error) {
			log.warn("Could not store the report for {}: {}", nativeMeetingId, error.getMessage());
		}
	}
}
