package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.responses.MeetingReportActionItem;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonSchemaUtils;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.TextUtils;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingReportService {

  private final AiProviderClient aiProviderClient;
  private final LocalPiiRedactionService piiRedactionService;
  private final JsonUtils jsonUtils;
  private final JsonSchemaUtils jsonSchemaUtils;

  public MeetingReportResponse generate(
      List<TranscriptSegmentResponse> segments,
      String modelOverride) {
    if (segments == null || segments.isEmpty()) {
      throw new InvalidRequestException("Transcript is empty; cannot generate a meeting report.");
    }

    String transcriptText = segments.stream()
        .map(seg -> "[" + TextUtils.nullToEmpty(seg.at()) + "] "
            + TextUtils.nullToEmpty(seg.speaker()) + ": "
            + TextUtils.nullToEmpty(seg.text()))
        .collect(Collectors.joining("\n"));

    String redactedTranscript = piiRedactionService.redact(transcriptText).text();

    String model = aiProviderClient.resolveReportModel(modelOverride);
    JsonNode raw = aiProviderClient.generateMeetingReport(
        redactedTranscript,
        reportSchema(),
        modelOverride);

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

  private JsonNode reportSchema() {
    return jsonSchemaUtils.object()
        .string("title")
        .nullableString("meetingDate")
        .nullableString("topic")
        .string("summary")
        .stringArray("topicsDiscussed")
        .stringArray("decisions")
        .objectArray("actionItems", item -> item
            .nullableString("owner")
            .string("task")
            .nullableString("due"))
        .stringArray("risksOrBlockers")
        .stringArray("nextSteps")
        .build();
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
