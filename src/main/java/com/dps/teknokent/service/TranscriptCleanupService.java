package com.dps.teknokent.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.util.JsonSchemaUtils;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The accent layer: one AI pass over a finished transcript that fixes words the recogniser misheard, in whatever
 * language the meeting was held in. Anything it cannot vouch for keeps the recogniser's own text.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptCleanupService {

	private static final String REDACTED_MARKER = "[REDACTED_";

	private final AiProviderClient aiProviderClient;
	private final LocalPiiRedactionService piiRedactionService;
	private final JsonSchemaUtils jsonSchemaUtils;
	private final AiFeatures aiFeatures;

	public List<TranscriptSegmentResponse> clean(List<TranscriptSegmentResponse> segments) {
		// The cleanup sends transcript lines out, so it goes with the meeting report's switch.
		if (segments == null || segments.isEmpty() || !aiFeatures.isEnabled(AiFeature.MEETING_REPORT)) {
			return segments;
		}
		// A line with an e-mail, phone or ID number never leaves the server; it stays as the recogniser wrote it.
		List<TranscriptSegmentResponse> sendable = segments.stream()
				.filter(segment -> segment.text() != null && !segment.text().isBlank())
				.filter(segment -> piiRedactionService.redact(segment.text()).totalRedacted() == 0)
				.toList();
		if (sendable.isEmpty()) {
			return segments;
		}
		Map<String, String> corrected;
		try {
			corrected = correctedTexts(aiProviderClient.cleanTranscript(lines(sendable), schema()));
		} catch (RuntimeException error) {
			log.warn("Transcript cleanup failed; keeping the raw transcript: {}", error.getMessage());
			return segments;
		}
		return segments.stream()
				.map(segment -> withText(segment, corrected.get(segment.id())))
				.toList();
	}

	private static String lines(List<TranscriptSegmentResponse> segments) {
		return segments.stream()
				.map(segment -> segment.id() + "\t" + segment.text().replace('\n', ' ').replace('\t', ' '))
				.collect(Collectors.joining("\n"));
	}

	private static Map<String, String> correctedTexts(JsonNode result) {
		Map<String, String> byId = new HashMap<>();
		for (JsonNode line : result.path("segments")) {
			String id = line.path("id").asText("");
			String text = line.path("text").asText("").trim();
			if (!id.isEmpty() && !text.isEmpty() && !text.contains(REDACTED_MARKER)) {
				byId.put(id, text);
			}
		}
		return byId;
	}

	private static TranscriptSegmentResponse withText(TranscriptSegmentResponse segment, String text) {
		return text == null
				? segment
				: new TranscriptSegmentResponse(segment.id(), segment.speaker(), text, segment.at());
	}

	private JsonNode schema() {
		return jsonSchemaUtils.object()
				.string("language")
				.objectArray("segments", line -> line.string("id").string("text"))
				.build();
	}
}
