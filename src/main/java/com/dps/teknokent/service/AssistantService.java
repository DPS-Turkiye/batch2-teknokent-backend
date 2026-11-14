package com.dps.teknokent.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.MeetingBotEndReason;
import com.dps.teknokent.domain.MeetingBotState;
import com.dps.teknokent.dto.provider.ProviderBotDetails;
import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderBotStatusResponse;
import com.dps.teknokent.dto.provider.ProviderBotSummary;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.BotStateResponse;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.MeetingProviderException;
import com.dps.teknokent.util.Texts;
import com.dps.teknokent.util.TimeFormatUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

	/** Code of the 409 the frontend shows as "every recording slot is busy". */
	public static final String RECORDING_SLOTS_FULL = "RECORDING_SLOTS_FULL";

	private final MeetingProviderClient providerClient;
	private final MeetingReportService meetingReportService;
	private final TranscriptBotCapacityService capacityService;
	private final AssistantMeetingAccessService accessService;
	private final AiFeatures aiFeatures;
	private final MeetingService meetingService;

	/**
	 * A bot asked for from the assistant, outside a booked meeting. It counts the bots running right now plus the
	 * booked meetings whose bots join within the hour, so booked recordings keep their slots; when the running count
	 * is unknown it refuses. Only a participant of a Menton meeting on that link, or an admin, may send one.
	 */
	public AddBotResponse addAdHocBot(@Nullable UUID callerId, AddBotRequest request) {
		MeetingUrlParser.Parsed link;
		try {
			link = MeetingUrlParser.parse(request.meetingUrl());
		} catch (IllegalArgumentException unsupported) {
			throw new InvalidRequestException(Texts.forRequest("error.meetingLinkUnsupported"));
		}
		var parsed = accessService.requireAccess(callerId, link.platform(), link.nativeMeetingId());
		Set<String> running = fromProvider("Could not read the running bots", this::runningBotKeys)
				.orElseThrow(() -> new MeetingProviderException("The running bots are unknown"));
		boolean alreadyThere = running.contains(botKey(parsed.platform(), parsed.nativeMeetingId()));
		if (alreadyThere) {
			AddBotResponse existing = fromProvider("Could not read the meeting's bot",
					() -> liveBot(parsed.platform(), parsed.nativeMeetingId()));
			if (existing != null) {
				return existing;
			}
		}
		// ponytail: no lock, so two requests at the same moment can both pass, and a meeting booked later does not see
		// ad-hoc bots; the dispatcher's retry on Vexa's 429 covers that until a shared ledger is worth it.
		if (!alreadyThere
				&& running.size() + capacityService.reservedWithinHour(Instant.now()) >= capacityService.capacity()) {
			throw new ConflictException(Texts.forRequest("error.recordingSlotsFull"), RECORDING_SLOTS_FULL);
		}
		try {
			return addBot(request);
		} catch (RestClientResponseException refused) {
			if (refused.getStatusCode().value() != HttpStatus.CONFLICT.value()) {
				throw new MeetingProviderException("Could not send the bot", refused);
			}
			// Vexa answers 409 when this link already has a bot, or when the signed-in bot account is busy elsewhere.
			AddBotResponse existing = fromProvider("Could not read the meeting's bot",
					() -> liveBot(parsed.platform(), parsed.nativeMeetingId()));
			if (existing != null) {
				return existing;
			}
			throw new ConflictException(Texts.forRequest("error.recordingBotBusy"));
		} catch (RuntimeException failure) {
			throw new MeetingProviderException("Could not send the bot", failure);
		}
	}

	/**
	 * Runs a call to the meeting provider for a caller; whatever goes wrong there is the provider's failure (502),
	 * not the caller's. The watchdog and dispatcher call the provider directly, as they read its status codes.
	 */
	private static <T> T fromProvider(String failure, Supplier<T> call) {
		try {
			return call.get();
		} catch (RuntimeException error) {
			throw new MeetingProviderException(failure, error);
		}
	}

	/** The bot already holding this meeting, or null, so a second send does not look like a failure. */
	private @Nullable AddBotResponse liveBot(String platform, String nativeMeetingId) {
		ProviderBotSummary latest = providerClient.findLatestBot(platform, nativeMeetingId);
		if (latest == null || !MeetingBotState.fromProvider(latest.status()).holdsMeeting()) {
			return null;
		}
		return new AddBotResponse(
				latest.id() != null ? String.valueOf(latest.id()) : "",
				platform,
				nativeMeetingId,
				latest.status(),
				"New meeting bot");
	}

	/** Where the meeting's latest recording bot is; checked like the other assistant calls. */
	public BotStateResponse botState(@Nullable UUID callerId, String platform, String nativeMeetingId) {
		var parsed = accessService.requireAccess(callerId, platform, nativeMeetingId);
		ProviderBotSummary latest = fromProvider("Could not read the bot state",
				() -> providerClient.findLatestBot(parsed.platform(), parsed.nativeMeetingId()));
		if (latest == null) {
			return new BotStateResponse(parsed.platform(), parsed.nativeMeetingId(), MeetingBotState.NONE, null, null);
		}
		MeetingBotState state = MeetingBotState.fromProvider(latest.status());
		boolean ended = state == MeetingBotState.COMPLETED || state == MeetingBotState.FAILED;
		return new BotStateResponse(
				parsed.platform(),
				parsed.nativeMeetingId(),
				state,
				ended ? MeetingBotEndReason.fromProvider(latest.completionReason()) : null,
				latest.createdAt());
	}

	/** Unchecked: for the dispatcher's booked meetings. Callers acting for a user go through addAdHocBot. */
	AddBotResponse addBot(AddBotRequest request) {
		var parsed = MeetingUrlParser.parse(request.meetingUrl());
		ProviderBotResponse body = providerClient.sendBot(
				parsed.platform(),
				parsed.nativeMeetingId(),
				MeetingUrlParser.canonicalUrl(parsed));
		// The bot is on its way even when the provider answered with no body, so that is not a failure.
		if (body == null) {
			return new AddBotResponse("", parsed.platform(), parsed.nativeMeetingId(), "requested", "New meeting bot");
		}

		return new AddBotResponse(
				body.id() != null ? body.id() : "",
				parsed.platform(),
				parsed.nativeMeetingId(),
				body.status() != null ? body.status() : "requested",
				body.title() != null ? body.title() : "New meeting bot");
	}

	public TranscriptResponse getTranscript(@Nullable UUID callerId, String platform, String nativeMeetingId) {
		var parsed = accessService.requireAccess(callerId, platform, nativeMeetingId);
		return fromProvider("Could not fetch the transcript",
				() -> getTranscript(parsed.platform(), parsed.nativeMeetingId()));
	}

	TranscriptResponse getTranscript(String platform, String nativeMeetingId) {
		ProviderTranscriptResponse body = providerClient.getTranscript(platform, nativeMeetingId);

		List<ProviderTranscriptSegment> raw = body != null && body.segments() != null ? body.segments() : List.of();

		double origin = transcriptOrigin(raw);
		List<TranscriptSegmentResponse> segments = raw.stream()
				.map(seg -> new TranscriptSegmentResponse(
						seg.segmentId() != null ? seg.segmentId() : "",
						seg.speaker() != null ? seg.speaker() : "Speaker",
						seg.text() != null ? seg.text() : "",
						TimeFormatUtils.formatTime(seg.start() == null ? null : seg.start() - origin)))
				.toList();

		return new TranscriptResponse(segments);
	}

	// Vexa 0.12 sends each start as Unix seconds; older versions sent seconds since the meeting began.
	private static final double EPOCH_SECONDS_FLOOR = 1_000_000_000d;

	/** Absolute starts count from the first segment, so the transcript reads 00:00, 00:12, ... */
	private static double transcriptOrigin(List<ProviderTranscriptSegment> segments) {
		return segments.stream()
				.map(ProviderTranscriptSegment::start)
				.filter(start -> start != null && start >= EPOCH_SECONDS_FLOOR)
				.min(Double::compare)
				.orElse(0d);
	}

	/** Stops the meeting's bot and closes the meeting, as its recording has ended. */
	public void stopBot(@Nullable UUID callerId, String platform, String nativeMeetingId) {
		var parsed = accessService.requireAccess(callerId, platform, nativeMeetingId);
		fromProvider("Could not stop the bot", () -> {
			stopBot(parsed.platform(), parsed.nativeMeetingId());
			return null;
		});
		meetingService.markBotStopped(parsed.nativeMeetingId());
	}

	/**
	 * A 404 means the provider holds no stoppable bot for the link: it already left, or it sits in Vexa's
	 * {@code needs_help} state, which its stop route does not reach and its own reaper ends. Either way there
	 * is nothing more to stop, so the meeting can be closed.
	 */
	void stopBot(String platform, String nativeMeetingId) {
		try {
			providerClient.stopBot(platform, nativeMeetingId);
		} catch (RestClientResponseException missing) {
			if (missing.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
				throw missing;
			}
			log.warn("No stoppable bot for {}; treating it as stopped", nativeMeetingId);
		}
	}

	public Optional<Set<String>> runningBotKeys() {
		ProviderBotStatusResponse body = providerClient.getRunningBots();
		if (body == null || body.runningBots() == null) {
			return Optional.empty();
		}
		return Optional.of(body.runningBots().stream()
				.filter(bot -> bot.nativeMeetingId() != null && !bot.nativeMeetingId().isBlank())
				.map(bot -> botKey(bot.platform(), bot.nativeMeetingId()))
				.collect(Collectors.toCollection(LinkedHashSet::new)));
	}

	public static String botKey(@Nullable String platform, @Nullable String nativeMeetingId) {
		return normalized(platform) + ":" + normalized(nativeMeetingId);
	}

	public @Nullable ProviderBotDetails botDetails(String platform, String nativeMeetingId) {
		return providerClient.getBot(platform, nativeMeetingId);
	}

	private static String normalized(@Nullable String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * The report a participant asks for. The transcript is always the provider's own for that meeting and the model
	 * is the configured one, so nobody can push their own text or pick a model through our key.
	 */
	public MeetingReportResponse generateMeetingReport(@Nullable UUID callerId, String platform,
			String nativeMeetingId) {
		var parsed = accessService.requireAccess(callerId, platform, nativeMeetingId);
		aiFeatures.require(AiFeature.MEETING_REPORT);
		TranscriptResponse transcript = fromProvider("Could not fetch the transcript for a report",
				() -> getTranscript(parsed.platform(), parsed.nativeMeetingId()));
		try {
			return generateMeetingReport(transcript.segments());
		} catch (InvalidRequestException refused) {
			throw refused;
		} catch (RuntimeException failure) {
			throw new MeetingProviderException("Could not write the meeting report", failure);
		}
	}

	/** For the watchdog, which has already cleaned the provider's transcript. */
	MeetingReportResponse generateMeetingReport(List<TranscriptSegmentResponse> segments) {
		return meetingReportService.generate(segments);
	}

}
