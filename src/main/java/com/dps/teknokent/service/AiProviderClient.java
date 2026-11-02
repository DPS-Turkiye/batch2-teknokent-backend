package com.dps.teknokent.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
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
	@Value("${ai.openrouter.report-model:openai/gpt-4o-mini}")
	private String openRouterReportModel;
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
		return structuredCompletion(instructions, redactedDocumentText, profileType + "_profile", schema, "profile");
	}

	/**
	 * Program phases from a syllabus. Dates are only filled when the text states them, or states week numbers
	 * that the program start date turns into dates.
	 */
	public JsonNode syllabusPhases(String redactedDocumentText, LocalDate programStart, LocalDate programEnd,
			JsonNode schema) {
		String instructions = """
			Extract the program phases (stages, modules, periods) from the supplied syllabus text, in order.
			- Extract only phases the source describes; never invent phases, dates or deliverables.
			- name: the phase title as written, in the source language, at most 120 characters.
			- expected_output: the phase's deliverables or outcome as one short line (at most 150 characters),
			  or null when the source names none.
			- start_date and end_date: ISO dates (YYYY-MM-DD). Use a date the source states. When the source
			  gives week or month numbers and the program start date is known, count from that date. Otherwise null.
			- Return at most 12 phases. Put extraction limitations in warnings.
			- The source may be Turkish, English, or contain both languages.
			Program start date: %s. Program end date: %s.
			""".formatted(programStart == null ? "unknown" : programStart, programEnd == null ? "unknown" : programEnd);
		return structuredCompletion(instructions, redactedDocumentText, "syllabus_phases", schema, "phase list");
	}

	private JsonNode structuredCompletion(
			String instructions, String userText, String schemaName, JsonNode schema, String resultName) {
		Map<String, Object> payload = Map.of(
				"model", profileModel(),
				"messages", List.of(
						Map.of("role", "system", "content", instructions),
						Map.of("role", "user", "content", userText)),
				"response_format", Map.of(
						"type", "json_schema",
						"json_schema", Map.of(
								"name", schemaName,
								"strict", true,
								"schema", schema)));
		JsonNode response = post(baseUrl() + "/chat/completions", payload);
		JsonNode message = response.path("choices").path(0).path("message");
		String content = message.path("content").asText("");
		if (content.isBlank()) {
			throw new InvalidRequestException("The AI provider returned an empty " + resultName + ".");
		}
		try {
			return jsonUtils.readTree(content);
		} catch (IllegalStateException error) {
			throw new InvalidRequestException("The AI provider returned an invalid structured " + resultName + ".");
		}
	}

	public JsonNode generateMeetingReport(
			String transcriptText,
			JsonNode schema,
			String modelOverride) {
		String model = (modelOverride != null && !modelOverride.isBlank())
				? modelOverride.trim()
				: reportModel();
		String schemaJson = jsonUtils.writeValue(schema);
		String instructions = """
			You are a mentorship meeting assistant that writes structured meeting reports.
			- Base every field only on the supplied transcript. Never invent facts.
			- Always fill "summary" with a clear meeting overview.
			- Fill meeting metadata: title, meetingDate (if mentioned, else null), topic.
			- topicsDiscussed: main subjects that were talked about.
			- decisions: concrete decisions made in the meeting.
			- actionItems: follow-ups with owner/task/due when available (due may be null).
			- risksOrBlockers and nextSteps: use [] when not present.
			- Write the summary and every other narrative field in the language the meeting was held in.
			- Return ONLY a single JSON object. No markdown fences, no commentary.
			- The JSON must match this schema:
			""" + schemaJson;

		return completeWithSchemaFallback(model, instructions, transcriptText, schema);
	}

	/** Corrects speech-to-text lines ("id<TAB>text") in the language the meeting was held in. */
	public JsonNode cleanTranscript(String transcriptLines, JsonNode schema) {
		String instructions = """
			You correct speech-to-text output from a mentorship meeting.
			- Each input line is "<id><TAB><text>". Work out the language of the meeting from the whole transcript.
			- Fix words the recogniser misheard (accents, names of tools and companies, Turkish and English mixed),
			  spelling and punctuation, in the language each line was spoken in.
			- Keep the meaning. Never add, drop, merge, split, translate or summarise lines.
			- Return every id exactly once with its text; return the text unchanged when it is already right.
			- Keep placeholders such as [REDACTED_EMAIL] exactly as they are.
			- Return ONLY a single JSON object. No markdown fences, no commentary.
			- The JSON must match this schema:
			""" + jsonUtils.writeValue(schema);
		return completeWithSchemaFallback(reportModel(), instructions, transcriptLines, schema);
	}

	private JsonNode completeWithSchemaFallback(
			String model, String instructions, String input, JsonNode schema) {
		boolean preferStrictSchema =
				model.startsWith("openai/") || model.startsWith("google/");
		try {
			return completeMeetingReport(model, instructions, input, schema, preferStrictSchema);
		} catch (InvalidRequestException firstError) {
			if (!preferStrictSchema) {
				throw firstError;
			}
			return completeMeetingReport(model, instructions, input, schema, false);
		}
	}

	private JsonNode completeMeetingReport(
			String model,
			String instructions,
			String transcriptText,
			JsonNode schema,
			boolean strictSchema) {
		return completeMeetingReport(
				model, instructions, transcriptText, schema, strictSchema, model.startsWith("google/"));
	}

	/**
	 * Google models are asked to skip reasoning tokens to keep reports cheap, but
	 * newer ones refuse the request outright when reasoning is switched off. Ask
	 * again without the field instead of failing the report.
	 */
	private JsonNode completeMeetingReport(
			String model,
			String instructions,
			String transcriptText,
			JsonNode schema,
			boolean strictSchema,
			boolean skipReasoning) {
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("model", model);
		payload.put("messages", List.of(
				Map.of("role", "system", "content", instructions),
				Map.of("role", "user", "content", transcriptText)));
		if (strictSchema) {
			payload.put("response_format", Map.of(
					"type", "json_schema",
					"json_schema", Map.of(
							"name", "meeting_report",
							"strict", true,
							"schema", schema)));
		} else {
			payload.put("response_format", Map.of("type", "json_object"));
		}
		if (skipReasoning) {
			payload.put("reasoning", Map.of("effort", "none"));
		}

		JsonNode response;
		try {
			response = post(baseUrl() + "/chat/completions", payload);
		} catch (InvalidRequestException error) {
			if (skipReasoning && requiresReasoning(error)) {
				return completeMeetingReport(
						model, instructions, transcriptText, schema, strictSchema, false);
			}
			if (strictSchema) {
				throw error;
			}
			Map<String, Object> plain = new java.util.LinkedHashMap<>();
			plain.put("model", model);
			plain.put("messages", List.of(
					Map.of("role", "system", "content", instructions),
					Map.of("role", "user", "content", transcriptText)));
			if (skipReasoning) {
				plain.put("reasoning", Map.of("effort", "none"));
			}
			response = post(baseUrl() + "/chat/completions", plain);
		}

		String content = extractMessageContent(response.path("choices").path(0).path("message"));
		if (content.isBlank()) {
			throw new InvalidRequestException("The AI provider returned an empty meeting report.");
		}
		try {
			return jsonUtils.readTree(jsonUtils.unwrapJsonContent(content));
		} catch (IllegalStateException error) {
			throw new InvalidRequestException("The AI provider returned an invalid meeting report.");
		}
	}

	/** The provider refused because this model will not run with reasoning disabled. */
	private static boolean requiresReasoning(InvalidRequestException error) {
		String message = error.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("reasoning");
	}

	private String extractMessageContent(JsonNode message) {
		JsonNode content = message.path("content");
		if (content.isTextual()) {
			return content.asText("");
		}
		if (content.isArray()) {
			StringBuilder joined = new StringBuilder();
			for (JsonNode part : content) {
				if (part.isTextual()) {
					joined.append(part.asText());
				} else if (part.has("text")) {
					joined.append(part.path("text").asText(""));
				}
			}
			return joined.toString();
		}
		return "";
	}

	public String resolveReportModel(String modelOverride) {
		if (modelOverride != null && !modelOverride.isBlank()) {
			return modelOverride.trim();
		}
		return reportModel();
	}

	public String resolveProfileModel(String modelOverride) {
		if (modelOverride != null && !modelOverride.isBlank()) {
			return modelOverride.trim();
		}
		return profileModel();
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

	private String reportModel() {
		if ("openrouter".equals(providerName())) {
			return openRouterReportModel != null && !openRouterReportModel.isBlank()
					? openRouterReportModel
					: openRouterProfileModel;
		}
		return openAiProfileModel;
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
