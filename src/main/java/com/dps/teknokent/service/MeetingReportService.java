package com.dps.teknokent.service;

import static com.dps.teknokent.util.JsonSchema.arrayOf;
import static com.dps.teknokent.util.JsonSchema.nullableString;
import static com.dps.teknokent.util.JsonSchema.object;
import static com.dps.teknokent.util.JsonSchema.property;
import static com.dps.teknokent.util.JsonSchema.string;
import static com.dps.teknokent.util.JsonSchema.stringArray;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.responses.MeetingReportActionItem;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class MeetingReportService {

	private final AiProviderClient aiProviderClient;
	private final LocalPiiRedactionService piiRedactionService;
	private final JsonUtils jsonUtils;

	public MeetingReportResponse generate(List<TranscriptSegmentResponse> segments) {
		if (segments == null || segments.isEmpty()) {
			throw new InvalidRequestException("Transcript is empty; cannot generate a meeting report.");
		}

		String transcriptText = segments.stream()
				.map(seg -> "[" + TextUtils.nullToEmpty(seg.at()) + "] "
						+ TextUtils.nullToEmpty(seg.speaker()) + ": "
						+ TextUtils.nullToEmpty(seg.text()))
				.collect(Collectors.joining("\n"));

		String redactedTranscript = piiRedactionService.redact(transcriptText).text();

		String model = aiProviderClient.resolveReportModel();
		JsonNode raw = aiProviderClient.generateMeetingReport(redactedTranscript, reportSchema());

		return new MeetingReportResponse(
				jsonUtils.textOrEmpty(raw, "title"),
				jsonUtils.nullableText(raw, "meetingDate"),
				jsonUtils.nullableText(raw, "topic"),
				jsonUtils.textOrEmpty(raw, "summary"),
				jsonUtils.stringList(raw.path("topicsDiscussed")),
				jsonUtils.stringList(raw.path("decisions")),
				actionItems(raw.path("actionItems")),
				jsonUtils.stringList(raw.path("risksOrBlockers")),
				jsonUtils.stringList(raw.path("nextSteps")),
				model);
	}

	private static JsonNode reportSchema() {
		return object(property("title", string()), property("meetingDate", nullableString()),
				property("topic", nullableString()), property("summary", string()),
				property("topicsDiscussed", stringArray()), property("decisions", stringArray()),
				property("actionItems", arrayOf(object(property("owner", nullableString()), property("task", string()),
						property("due", nullableString())))),
				property("risksOrBlockers", stringArray()), property("nextSteps", stringArray()));
	}

	private List<MeetingReportActionItem> actionItems(JsonNode node) {
		List<MeetingReportActionItem> items = new ArrayList<>();
		if (!node.isArray()) {
			return items;
		}
		for (JsonNode item : node) {
			items.add(new MeetingReportActionItem(
					jsonUtils.nullableText(item, "owner"),
					jsonUtils.textOrEmpty(item, "task"),
					jsonUtils.nullableText(item, "due")));
		}
		return items;
	}
}
