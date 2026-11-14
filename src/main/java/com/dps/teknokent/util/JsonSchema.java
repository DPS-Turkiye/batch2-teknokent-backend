package com.dps.teknokent.util;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON Schemas for the AI provider's structured output. Objects are built for strict mode: every property is
 * required and nothing else is allowed, so an optional value is a nullable type rather than a missing key.
 */
public final class JsonSchema {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private JsonSchema() {
	}

	public record Property(String name, JsonNode schema) {
	}

	public static Property property(String name, JsonNode schema) {
		return new Property(name, schema);
	}

	public static ObjectNode object(Property... properties) {
		ObjectNode schema = NODES.objectNode().put("type", "object").put("additionalProperties", false);
		ObjectNode fields = schema.putObject("properties");
		ArrayNode required = schema.putArray("required");
		for (Property property : properties) {
			fields.set(property.name(), property.schema());
			required.add(property.name());
		}
		return schema;
	}

	public static ObjectNode string() {
		return type("string");
	}

	public static ObjectNode integer() {
		return type("integer");
	}

	public static ObjectNode bool() {
		return type("boolean");
	}

	public static ObjectNode nullableString() {
		return nullable("string");
	}

	public static ObjectNode nullableInteger() {
		return nullable("integer");
	}

	public static ObjectNode stringArray() {
		return arrayOf(string());
	}

	public static ObjectNode arrayOf(JsonNode items) {
		ObjectNode schema = type("array");
		schema.set("items", items);
		return schema;
	}

	public static ObjectNode enumOf(String... values) {
		ObjectNode schema = string();
		ArrayNode allowed = schema.putArray("enum");
		for (String value : values) {
			allowed.add(value);
		}
		return schema;
	}

	/** Adds a description the model reads as an instruction for this property. */
	public static ObjectNode described(ObjectNode schema, String description) {
		return schema.put("description", description);
	}

	private static ObjectNode type(String type) {
		return NODES.objectNode().put("type", type);
	}

	private static ObjectNode nullable(String type) {
		ObjectNode schema = NODES.objectNode();
		schema.putArray("type").add(type).add("null");
		return schema;
	}
}
