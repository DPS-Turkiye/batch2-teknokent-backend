package com.dps.teknokent.util;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonUtilsTest {

	private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

	@Test
	void readsAndWritesJsonThroughOneSharedCodec() {
		String json = jsonUtils.writeValue(Map.of("name", "NovaMed"));

		assertThat(jsonUtils.readTree(json).path("name").asText()).isEqualTo("NovaMed");
	}

	@Test
	void reportsInvalidJsonConsistently() {
		assertThatThrownBy(() -> jsonUtils.readTree("{"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Invalid JSON data.");
	}
}
