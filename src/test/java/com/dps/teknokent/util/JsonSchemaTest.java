package com.dps.teknokent.util;

import static com.dps.teknokent.util.JsonSchema.arrayOf;
import static com.dps.teknokent.util.JsonSchema.bool;
import static com.dps.teknokent.util.JsonSchema.described;
import static com.dps.teknokent.util.JsonSchema.enumOf;
import static com.dps.teknokent.util.JsonSchema.integer;
import static com.dps.teknokent.util.JsonSchema.nullableInteger;
import static com.dps.teknokent.util.JsonSchema.nullableString;
import static com.dps.teknokent.util.JsonSchema.object;
import static com.dps.teknokent.util.JsonSchema.property;
import static com.dps.teknokent.util.JsonSchema.string;
import static com.dps.teknokent.util.JsonSchema.stringArray;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class JsonSchemaTest {

	@Test
	void anObjectRequiresEveryPropertyAndAllowsNoOther() {
		JsonNode schema = object(property("title", string()), property("topic", nullableString()));

		assertThat(schema.toString()).isEqualTo("{\"type\":\"object\",\"additionalProperties\":false,"
				+ "\"properties\":{\"title\":{\"type\":\"string\"},\"topic\":{\"type\":[\"string\",\"null\"]}},"
				+ "\"required\":[\"title\",\"topic\"]}");
	}

	@Test
	void arraysCarryTheirItemSchema() {
		JsonNode schema = arrayOf(object(property("task", string())));

		assertThat(schema.path("type").asString()).isEqualTo("array");
		assertThat(schema.path("items").path("required").toString()).isEqualTo("[\"task\"]");
		assertThat(stringArray().path("items").path("type").asString()).isEqualTo("string");
	}

	@Test
	void scalarsEnumsAndDescriptions() {
		assertThat(integer().path("type").asString()).isEqualTo("integer");
		assertThat(bool().path("type").asString()).isEqualTo("boolean");
		assertThat(nullableInteger().path("type").toString()).isEqualTo("[\"integer\",\"null\"]");
		assertThat(enumOf("explicit", "inferred").toString())
				.isEqualTo("{\"type\":\"string\",\"enum\":[\"explicit\",\"inferred\"]}");
		assertThat(described(string(), "In Turkish").path("description").asString()).isEqualTo("In Turkish");
	}
}
