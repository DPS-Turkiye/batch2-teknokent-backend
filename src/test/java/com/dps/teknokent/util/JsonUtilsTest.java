package com.dps.teknokent.util;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
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

	@Test
	void unwrapsJsonFromMarkdownFences() {
		String content = "```json\n{\"title\": \"Sprint\"}\n```";

		assertThat(jsonUtils.unwrapJsonContent(content)).isEqualTo("{\"title\": \"Sprint\"}");
	}

	@Test
	void unwrapsJsonSurroundedByCommentary() {
		String content = "Here is the report: {\"title\": \"Sprint\"} Let me know if you need more.";

		assertThat(jsonUtils.unwrapJsonContent(content)).isEqualTo("{\"title\": \"Sprint\"}");
	}

	@Test
	void keepsPlainJsonAndNonJsonContentUnchanged() {
		assertThat(jsonUtils.unwrapJsonContent("{\"a\":1}")).isEqualTo("{\"a\":1}");
		assertThat(jsonUtils.unwrapJsonContent("no json here")).isEqualTo("no json here");
		assertThat(jsonUtils.unwrapJsonContent(null)).isEmpty();
	}

	@Test
	void readsTextFieldsWithBlankAndMissingValuesNormalized() {
		JsonNode node = jsonUtils.readTree("{\"title\":\"Sprint\",\"topic\":\"  \",\"owner\":null}");

		assertThat(jsonUtils.textOrEmpty(node, "title")).isEqualTo("Sprint");
		assertThat(jsonUtils.textOrEmpty(node, "missing")).isEmpty();
		assertThat(jsonUtils.nullableText(node, "title")).isEqualTo("Sprint");
		assertThat(jsonUtils.nullableText(node, "topic")).isNull();
		assertThat(jsonUtils.nullableText(node, "owner")).isNull();
		assertThat(jsonUtils.nullableText(node, "missing")).isNull();
	}

	@Test
	void readsStringListsSkippingBlanksAndNonArrays() {
		JsonNode node = jsonUtils.readTree("{\"tags\":[\"growth\",\"  \",\"churn\"],\"note\":\"text\"}");

		assertThat(jsonUtils.stringList(node.path("tags"))).containsExactly("growth", "churn");
		assertThat(jsonUtils.stringList(node.path("note"))).isEmpty();
		assertThat(jsonUtils.stringList(node.path("missing"))).isEmpty();
	}
}
