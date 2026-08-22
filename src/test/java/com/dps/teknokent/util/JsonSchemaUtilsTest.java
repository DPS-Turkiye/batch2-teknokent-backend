package com.dps.teknokent.util;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaUtilsTest {

	private final JsonSchemaUtils jsonSchemaUtils = new JsonSchemaUtils(new JsonUtils(new ObjectMapper()));

	@Test
	void buildsStrictObjectSchemaWithEveryFieldRequired() {
		JsonNode schema = jsonSchemaUtils.object()
				.string("title")
				.nullableString("topic")
				.build();

		assertThat(schema.path("type").asText()).isEqualTo("object");
		assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
		assertThat(schema.path("properties").path("title").path("type").asText()).isEqualTo("string");
		assertThat(schema.path("properties").path("topic").path("type").toString()).isEqualTo("[\"string\",\"null\"]");
		assertThat(schema.path("required").toString()).isEqualTo("[\"title\",\"topic\"]");
	}

	@Test
	void buildsStringArrayFields() {
		JsonNode schema = jsonSchemaUtils.object().stringArray("decisions").build();
		JsonNode decisions = schema.path("properties").path("decisions");

		assertThat(decisions.path("type").asText()).isEqualTo("array");
		assertThat(decisions.path("items").path("type").asText()).isEqualTo("string");
	}

	@Test
	void buildsNestedObjectArrayFields() {
		JsonNode schema = jsonSchemaUtils.object()
				.objectArray("actionItems", item -> item
						.string("task")
						.nullableString("due"))
				.build();
		JsonNode items = schema.path("properties").path("actionItems").path("items");

		assertThat(schema.path("properties").path("actionItems").path("type").asText()).isEqualTo("array");
		assertThat(items.path("type").asText()).isEqualTo("object");
		assertThat(items.path("additionalProperties").asBoolean()).isFalse();
		assertThat(items.path("properties").path("task").path("type").asText()).isEqualTo("string");
		assertThat(items.path("required").toString()).isEqualTo("[\"task\",\"due\"]");
	}
}
