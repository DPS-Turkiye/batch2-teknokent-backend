package com.dps.teknokent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class JsonUtils {

	private final ObjectMapper objectMapper;

	/** Spring's mapper, so trees and values follow the same settings (java.time, naming) as the HTTP layer. */
	public JsonUtils(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public JsonNode readTree(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JacksonException error) {
			throw new IllegalStateException("Invalid JSON data.", error);
		}
	}

	public String writeValue(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JacksonException error) {
			throw new IllegalStateException("Value could not be serialized as JSON.", error);
		}
	}

	public JsonNode valueToTree(Object value) {
		return objectMapper.valueToTree(value);
	}

	public String unwrapJsonContent(@Nullable String content) {
		if (content == null) {
			return "";
		}
		String trimmed = content.trim();
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			if (firstNewline > 0) {
				trimmed = trimmed.substring(firstNewline + 1);
			}
			int fence = trimmed.lastIndexOf("```");
			if (fence >= 0) {
				trimmed = trimmed.substring(0, fence);
			}
			trimmed = trimmed.trim();
		}
		int start = trimmed.indexOf('{');
		int end = trimmed.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return trimmed.substring(start, end + 1);
		}
		return trimmed;
	}

	/** Converts a node to a plain map. */
	public Map<String, Object> toMap(JsonNode node) {
		return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
		});
	}

	public ObjectNode createObjectNode() {
		return objectMapper.createObjectNode();
	}

	public String textOrEmpty(JsonNode node, String field) {
		String value = node.path(field).asString("");
		return value == null ? "" : value;
	}

	public @Nullable String nullableText(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isNull() || value.isMissingNode()) {
			return null;
		}
		String text = value.asString(null);
		return text != null && text.isBlank() ? null : text;
	}

	public List<String> stringList(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (!node.isArray()) {
			return values;
		}
		for (JsonNode item : node) {
			String value = item.asString("");
			if (!value.isBlank()) {
				values.add(value);
			}
		}
		return values;
	}
}
