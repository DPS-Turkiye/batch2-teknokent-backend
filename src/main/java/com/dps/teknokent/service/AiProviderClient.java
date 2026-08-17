package com.dps.teknokent.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class AiProviderClient {

	private final JsonUtils jsonUtils;
	private final HttpClient httpClient;

	@Value("${ai.provider:openai}")
	private String provider;
	@Value("${ai.openai.api-key:}")
	private String openAiApiKey;
	@Value("${ai.openai.base-url:https://api.openai.com/v1}")
	private String openAiBaseUrl;
	@Value("${ai.openai.profile-model:gpt-4o-mini}")
	private String openAiProfileModel;
	@Value("${ai.openai.embedding-model:text-embedding-3-small}")
	private String openAiEmbeddingModel;
	@Value("${ai.openrouter.api-key:}")
	private String openRouterApiKey;
	@Value("${ai.openrouter.base-url:https://openrouter.ai/api/v1}")
	private String openRouterBaseUrl;
	@Value("${ai.openrouter.profile-model:openai/gpt-4o-mini}")
	private String openRouterProfileModel;
	@Value("${ai.openrouter.embedding-model:openai/text-embedding-3-small}")
	private String openRouterEmbeddingModel;
	@Value("${ai.openrouter.app-name:TeamTNT Spring Backend}")
	private String openRouterAppName;

	public AiProviderClient(JsonUtils jsonUtils) {
		this.jsonUtils = jsonUtils;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	}

	public JsonNode structuredProfile(String profileType, String redactedDocumentText, JsonNode schema) {
		String instructions = """
			Extract a structured profile from the supplied PDF text.
			- Extract only information supported by the source; never invent facts.
			- Use null for unavailable scalar values and [] for unavailable lists.
			- Keep evidence quotes short and verbatim and preserve [Page N] page numbers.
			- Mark evidence as explicit or inferred.
			- Do not return age, photographs, marital status, addresses, phone numbers, or emails.
			- Put information requiring a follow-up form in missing_fields.
			- Put document/extraction limitations in warnings.
			- The source may be Turkish, English, or contain both languages.
			""";
		Map<String, Object> payload = Map.of(
				"model", profileModel(),
				"messages", List.of(
						Map.of("role", "system", "content", instructions),
						Map.of("role", "user", "content", redactedDocumentText)),
				"response_format", Map.of(
						"type", "json_schema",
						"json_schema", Map.of(
								"name", profileType + "_profile",
								"strict", true,
								"schema", schema)));
		JsonNode response = post(baseUrl() + "/chat/completions", payload);
		JsonNode message = response.path("choices").path(0).path("message");
		String content = message.path("content").asText("");
		if (content.isBlank()) {
			throw new InvalidRequestException("The AI provider returned an empty profile.");
		}
		try {
			return jsonUtils.readTree(content);
		} catch (IllegalStateException error) {
			throw new InvalidRequestException("The AI provider returned an invalid structured profile.");
		}
	}

	public List<List<Double>> embeddings(List<String> texts) {
		if (texts.isEmpty()) return List.of();
		JsonNode response = post(baseUrl() + "/embeddings", Map.of("model", embeddingModel(), "input", texts));
		JsonNode data = response.path("data");
		if (!data.isArray() || data.size() != texts.size()) {
			throw new IllegalStateException("AI provider returned an invalid embedding count.");
		}
		List<List<Double>> result = new ArrayList<>();
		for (JsonNode item : data) {
			List<Double> vector = new ArrayList<>();
			item.path("embedding").forEach(value -> vector.add(value.asDouble()));
			if (vector.isEmpty()) throw new IllegalStateException("AI provider returned an empty embedding.");
			result.add(vector);
		}
		return result;
	}

	public String providerName() {
		return "openrouter".equalsIgnoreCase(provider) ? "openrouter" : "openai";
	}

	public String embeddingModel() {
		return "openrouter".equals(providerName()) ? openRouterEmbeddingModel : openAiEmbeddingModel;
	}

	private String profileModel() {
		return "openrouter".equals(providerName()) ? openRouterProfileModel : openAiProfileModel;
	}

	private String baseUrl() {
		return trimSlash("openrouter".equals(providerName()) ? openRouterBaseUrl : openAiBaseUrl);
	}

	private String apiKey() {
		return "openrouter".equals(providerName()) ? openRouterApiKey : openAiApiKey;
	}

	private JsonNode post(String url, Object payload) {
		if (apiKey() == null || apiKey().isBlank()) {
			throw new InvalidRequestException(providerName().toUpperCase(Locale.ROOT) + " API key is not configured.");
		}
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(90))
					.header("Authorization", "Bearer " + apiKey())
					.header("Content-Type", "application/json");
			if ("openrouter".equals(providerName())) {
				builder.header("X-Title", openRouterAppName);
			}
			HttpResponse<String> response = httpClient.send(
					builder.POST(HttpRequest.BodyPublishers.ofString(jsonUtils.writeValue(payload))).build(),
					HttpResponse.BodyHandlers.ofString());
			JsonNode body = response.body().isBlank() ? jsonUtils.createObjectNode() : jsonUtils.readTree(response.body());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				String detail = body.path("error").path("message").asText(body.path("detail").asText("AI request failed."));
				throw new InvalidRequestException(detail);
			}
			return body;
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new InvalidRequestException("AI request was interrupted.");
		} catch (IOException error) {
			throw new InvalidRequestException("The AI provider could not be reached.");
		}
	}

	private String trimSlash(String value) {
		return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
