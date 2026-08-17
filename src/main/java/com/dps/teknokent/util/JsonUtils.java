package com.dps.teknokent.util;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class JsonUtils {

	private final ObjectMapper objectMapper;

	public JsonUtils() {
		this(new ObjectMapper());
	}

	public JsonUtils(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public JsonNode readTree(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JsonProcessingException error) {
			throw new IllegalStateException("Invalid JSON data.", error);
		}
	}

	public String writeValue(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException error) {
			throw new IllegalStateException("Value could not be serialized as JSON.", error);
		}
	}

	public JsonNode valueToTree(Object value) {
		return objectMapper.valueToTree(value);
	}

	public ObjectNode createObjectNode() {
		return objectMapper.createObjectNode();
	}
}
