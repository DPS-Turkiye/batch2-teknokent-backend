package com.dps.teknokent.util;

import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JsonSchemaUtils {

	private final JsonUtils jsonUtils;

	public ObjectSchemaBuilder object() {
		return new ObjectSchemaBuilder(jsonUtils.createObjectNode());
	}

	public static final class ObjectSchemaBuilder {

		private final ObjectNode schema;
		private final ObjectNode properties;
		private final ArrayNode required;

		private ObjectSchemaBuilder(ObjectNode schema) {
			this.schema = schema;
			this.schema.put("type", "object");
			this.schema.put("additionalProperties", false);
			this.properties = schema.putObject("properties");
			this.required = schema.putArray("required");
		}

		public ObjectSchemaBuilder string(String name) {
			return field(name, schema.objectNode().put("type", "string"));
		}

		public ObjectSchemaBuilder nullableString(String name) {
			ObjectNode node = schema.objectNode();
			ArrayNode types = node.putArray("type");
			types.add("string");
			types.add("null");
			return field(name, node);
		}

		public ObjectSchemaBuilder stringArray(String name) {
			return field(name, arrayNode(schema.objectNode().put("type", "string")));
		}

		public ObjectSchemaBuilder objectArray(String name, Consumer<ObjectSchemaBuilder> items) {
			ObjectSchemaBuilder itemBuilder = new ObjectSchemaBuilder(schema.objectNode());
			items.accept(itemBuilder);
			return field(name, arrayNode(itemBuilder.build()));
		}

		public JsonNode build() {
			return schema;
		}

		private ObjectSchemaBuilder field(String name, JsonNode fieldSchema) {
			properties.set(name, fieldSchema);
			required.add(name);
			return this;
		}

		private JsonNode arrayNode(JsonNode itemSchema) {
			ObjectNode node = schema.objectNode().put("type", "array");
			node.set("items", itemSchema);
			return node;
		}
	}
}
