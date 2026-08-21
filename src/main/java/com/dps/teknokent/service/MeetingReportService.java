package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.responses.MeetingReportActionItem;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingReportService {

  private final AiProviderClient aiProviderClient;
  private final JsonUtils jsonUtils;

  public MeetingReportResponse generate(
      List<TranscriptSegmentResponse> segments,
      String modelOverride) {
    if (segments == null || segments.isEmpty()) {
      throw new InvalidRequestException("Transcript is empty; cannot generate a meeting report.");
    }

    String transcriptText = segments.stream()
        .map(seg -> "[" + nullToEmpty(seg.at()) + "] "
            + nullToEmpty(seg.speaker()) + ": "
            + nullToEmpty(seg.text()))
        .collect(Collectors.joining("\n"));

    String model = aiProviderClient.resolveReportModel(modelOverride);
    JsonNode raw = aiProviderClient.generateMeetingReport(
        transcriptText,
        reportSchema(),
        modelOverride);

    return new MeetingReportResponse(
        textOrEmpty(raw, "title"),
        nullableText(raw, "meetingDate"),
        nullableText(raw, "topic"),
        textOrEmpty(raw, "summary"),
        stringList(raw.path("topicsDiscussed")),
        stringList(raw.path("decisions")),
        actionItems(raw.path("actionItems")),
        stringList(raw.path("risksOrBlockers")),
        stringList(raw.path("nextSteps")),
        model);
  }

  private JsonNode reportSchema() {
    return objectSchema(List.of(
        field("title", string()),
        field("meetingDate", nullableString()),
        field("topic", nullableString()),
        field("summary", string()),
        field("topicsDiscussed", stringArray()),
        field("decisions", stringArray()),
        field("actionItems", arrayOf(objectSchema(List.of(
            field("owner", nullableString()),
            field("task", string()),
            field("due", nullableString()))))),
        field("risksOrBlockers", stringArray()),
        field("nextSteps", stringArray())));
  }

  private List<MeetingReportActionItem> actionItems(JsonNode node) {
    List<MeetingReportActionItem> items = new ArrayList<>();
    if (!node.isArray()) {
      return items;
    }
    for (JsonNode item : node) {
      items.add(new MeetingReportActionItem(
          nullableText(item, "owner"),
          textOrEmpty(item, "task"),
          nullableText(item, "due")));
    }
    return items;
  }

  private List<String> stringList(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (!node.isArray()) {
      return values;
    }
    for (JsonNode item : node) {
      String value = item.asText("");
      if (!value.isBlank()) {
        values.add(value);
      }
    }
    return values;
  }

  private String textOrEmpty(JsonNode node, String field) {
    String value = node.path(field).asText("");
    return value == null ? "" : value;
  }

  private String nullableText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isNull() || value.isMissingNode()) {
      return null;
    }
    String text = value.asText(null);
    return text != null && text.isBlank() ? null : text;
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private record Field(String name, JsonNode schema) {}

  private Field field(String name, JsonNode schema) {
    return new Field(name, schema);
  }

  private JsonNode objectSchema(List<Field> fields) {
    ObjectNode schema = jsonUtils.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    ObjectNode properties = schema.putObject("properties");
    ArrayNode required = schema.putArray("required");
    fields.forEach(field -> {
      properties.set(field.name(), field.schema());
      required.add(field.name());
    });
    return schema;
  }

  private JsonNode string() {
    return jsonUtils.createObjectNode().put("type", "string");
  }

  private JsonNode nullableString() {
    return typeUnion("string", "null");
  }

  private JsonNode stringArray() {
    return arrayOf(string());
  }

  private JsonNode arrayOf(JsonNode itemSchema) {
    ObjectNode schema = jsonUtils.createObjectNode().put("type", "array");
    schema.set("items", itemSchema);
    return schema;
  }

  private JsonNode typeUnion(String... types) {
    ObjectNode schema = jsonUtils.createObjectNode();
    ArrayNode type = schema.putArray("type");
    for (String value : types) {
      type.add(value);
    }
    return schema;
  }
}
