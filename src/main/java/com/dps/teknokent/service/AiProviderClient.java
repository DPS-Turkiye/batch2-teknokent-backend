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

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.AiProperties;
import com.dps.teknokent.config.AiProperties.AiProvider;
import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * The only class that talks to the AI provider. A provider that cannot be reached, refuses our credentials or fails
 * on its side is answered as a 502 with a translated text; the provider's own message goes to the log only, since
 * it is written for us, not for the user, and may name the key, the model or the account.
 */
@Service
@Slf4j
@EnableConfigurationProperties(AiProperties.class)
public class AiProviderClient {

	private final JsonUtils jsonUtils;
	// Checked again here, at the only place that talks to the provider, so a caller that forgets its own check
	// still sends nothing for a switched-off feature.
	private final AiFeatures aiFeatures;
	private final AiProperties properties;
	private final HttpClient httpClient;

	public AiProviderClient(JsonUtils jsonUtils, AiFeatures aiFeatures, AiProperties properties) {
		this.jsonUtils = jsonUtils;
		this.aiFeatures = aiFeatures;
		this.properties = properties;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	}

	public JsonNode structuredProfile(String profileType, String redactedDocumentText, JsonNode schema) {
		aiFeatures.require(AiFeature.PROFILE_PARSING);
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
	 * A week-by-week program read from a weekly program file, with the phases its weeks fall into. The caller turns
	 * week numbers into dates; the dates the source prints are only returned to be checked against the program.
	 */
	public JsonNode weeklyProgram(String redactedDocumentText, LocalDate programStart, LocalDate programEnd,
			JsonNode schema) {
		Integer totalWeeks = ProgramCalendar.totalWeeks(programStart, programEnd);
		String instructions = """
				The supplied text is a program's week-by-week schedule (often a table with one row per week). Read it
				into weeks, then group the weeks into phases.
				Weeks: one entry per week row of the source, in order.
				- week: the week number the source gives (for example "3 · ZİYARET / GEZİ 1" is week 3). When the source
				  gives no number, count the rows from 1.
				- source_date: the date the source prints for that week, as an ISO date (YYYY-MM-DD), or null. Copy it
				  even when it does not fit the program calendar; never change it to fit.
				- title: the week's own name or theme as written (for example "Keşfetmeye Başla"); when the week has no
				  name, its main session title. At most 100 characters.
				- topics: what is taught: the session or training titles, then speakers as one line starting
				  "Konuşmacı: ", then every recommended book or film as its own line starting "Kitap: " or "Film: ".
				- activities: what participants attend or do together: every workshop cell as a line starting
				  "Workshop: " (even when it only names who runs it), visits and trips as lines starting "Ziyaret: ",
				  with any date or place the source gives.
				- Keep every non-empty cell of a week row in one of these lists; drop nothing the row says.
				- deliverables: the tasks participants must complete or hand in that week, one per line.
				- Lines are short (at most 150 characters), at most 6 per list, and an empty list when the source has
				  nothing. Leave out empty placeholders such as "–".
				- phase: the name of the phase the week belongs to; it must equal one of the phase names below.
				Phases: consecutive weeks that work toward the same goal, in order (for example a problem phase, an idea
				or solution phase, a market and business model phase, a validation and pitch phase).
				- Use the phase or module headings the source gives when it has them. Otherwise analyse the weeks'
				  themes, topics and tasks and propose short phase names in the source language (at most 60 characters).
				- first_week and last_week: the first and last week number of the phase. Every week belongs to exactly
				  one phase; phases never overlap and follow the week order.
				- A visit, trip or break week belongs to the phase around it; it is not a phase of its own unless the
				  source says so.
				- expected_output: what participants should have at the end of the phase, as one short line drawn from
				  its weeks' tasks (at most 150 characters), or null.
				- Return between 2 and 8 phases when the program has at least 4 weeks.
				Notes: events or notes that are not one week's content (for example a demo day after the last week, or a
				general note under the table), one short line each with any date the source gives.
				Warnings: problems reading the source, one short line each.
				- Use only what the source says; never invent sessions, tasks, dates or people.
				- Write titles, lines and names in the source language. The source may be Turkish, English, or both.
				Program start date: %s. Program end date: %s. Program weeks: %s.
				""".formatted(programStart == null ? "unknown" : programStart,
				programEnd == null ? "unknown" : programEnd,
				totalWeeks == null ? "unknown" : totalWeeks);
		return structuredCompletion(instructions, redactedDocumentText, "weekly_program", schema, "weekly program");
	}

	/**
	 * Program phases from a syllabus. Dates are only filled when the text states them, or states week numbers
	 * that the program start date turns into dates.
	 */
	public JsonNode syllabusPhases(String redactedDocumentText, LocalDate programStart, LocalDate programEnd,
			JsonNode schema) {
		aiFeatures.require(AiFeature.SYLLABUS);
		Integer totalWeeks = ProgramCalendar.totalWeeks(programStart, programEnd);
		String instructions = """
				Extract the program phases (stages, modules, periods) from the supplied syllabus text, in order.
				- Extract only phases the source describes; never invent phases, dates or deliverables.
				- name: the phase title as written, in the source language, at most 120 characters.
				- expected_output: the phase's deliverables or outcome as one short line (at most 150 characters),
				  or null when the source names none.
				- start_date and end_date: ISO dates (YYYY-MM-DD). Use a date the source states. When the source
				  gives week or month numbers and the program start date is known, count from that date. Otherwise null.
				- Return at most 12 phases. Put extraction limitations in warnings.
				Also work out the program week by week, in weeks:
				- One entry per program week the source covers. Week 1 starts on the program start date and every week
				  is 7 days; week: the 1-based week number, start_date: that week's first day as an ISO date, or null
				  when the program start date is unknown.
				- title: what the week is about, at most 100 characters. topics, activities and deliverables: short
				  lines (at most 150 characters each, at most 6 per list) for what is covered, what participants do,
				  and what they hand in that week; an empty list when the source names none.
				- When the source describes the week itself, set inferred to false. When it only gives a phase or
				  module lasting several weeks, spread that phase's own content over its weeks and set inferred to true.
				- Use only content the source gives; never invent topics, tasks or deliverables, and never go past the
				  last program week.
				- Write titles and lines in the source language.
				- The source may be Turkish, English, or contain both languages.
				Program start date: %s. Program end date: %s. Program weeks: %s.
				""".formatted(programStart == null ? "unknown" : programStart,
				programEnd == null ? "unknown" : programEnd,
				totalWeeks == null ? "unknown" : totalWeeks);
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
		String content = message.path("content").asString("");
		if (content.isBlank()) {
			throw new InvalidRequestException("The AI provider returned an empty " + resultName + ".");
		}
		try {
			return jsonUtils.readTree(content);
		} catch (IllegalStateException error) {
			throw new InvalidRequestException("The AI provider returned an invalid structured " + resultName + ".");
		}
	}

	public JsonNode generateMeetingReport(String transcriptText, JsonNode schema) {
		aiFeatures.require(AiFeature.MEETING_REPORT);
		String model = reportModel();
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
		aiFeatures.require(AiFeature.MEETING_REPORT);
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

	/** Picks, for each open task of someone who left a team, the remaining member best placed to take it over. */
	public JsonNode taskHandover(String teamAndTasks, JsonNode schema) {
		aiFeatures.require(AiFeature.TASK_HANDOVER);
		String instructions = """
				You hand over the open tasks of someone who left a startup team to the members who stay.
				- The input lists the remaining members (id, job title, how many open tasks they already have, and
				  whether they lead the team) and the tasks to hand over (id, title, description).
				- For every task pick exactly one member whose job title fits the task best; when several fit equally,
				  prefer the one with fewer open tasks. Use the team lead when nobody fits.
				- Use only the given ids. Return every task id exactly once.
				- Return ONLY a single JSON object. No markdown fences, no commentary.
				- The JSON must match this schema:
				""" + jsonUtils.writeValue(schema);
		return completeWithSchemaFallback(reportModel(), instructions, teamAndTasks, schema);
	}

	/**
	 * Asks for the strict JSON schema first where the model family supports it, and asks again for any JSON object
	 * only when the strict attempt was refused by the provider (400/422) or answered with something unreadable. An
	 * outage, a 5xx or a refused key fails at once: a second request would fail the same way.
	 */
	private JsonNode completeWithSchemaFallback(
			String model, String instructions, String input, JsonNode schema) {
		boolean preferStrictSchema = model.startsWith("openai/") || model.startsWith("google/");
		try {
			try {
				return completeMeetingReport(model, instructions, input, schema, preferStrictSchema);
			} catch (RejectedByProvider | InvalidRequestException firstError) {
				if (!preferStrictSchema) {
					throw firstError;
				}
				return completeMeetingReport(model, instructions, input, schema, false);
			}
		} catch (RejectedByProvider rejected) {
			throw providerFailure("every response format was refused: " + rejected.getMessage());
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
			response = postAllowingRejection(baseUrl() + "/chat/completions", payload);
		} catch (RejectedByProvider error) {
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
			response = postAllowingRejection(baseUrl() + "/chat/completions", plain);
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
	private static boolean requiresReasoning(RejectedByProvider error) {
		String message = error.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("reasoning");
	}

	private String extractMessageContent(JsonNode message) {
		JsonNode content = message.path("content");
		if (content.isString()) {
			return content.asString("");
		}
		if (content.isArray()) {
			StringBuilder joined = new StringBuilder();
			for (JsonNode part : content) {
				if (part.isString()) {
					joined.append(part.asString());
				} else if (part.has("text")) {
					joined.append(part.path("text").asString(""));
				}
			}
			return joined.toString();
		}
		return "";
	}

	public String resolveReportModel() {
		return reportModel();
	}

	public List<List<Double>> embeddings(List<String> texts) {
		aiFeatures.require(AiFeature.MATCHING);
		if (texts.isEmpty()) {
			return List.of();
		}
		JsonNode response = post(baseUrl() + "/embeddings", Map.of("model", embeddingModel(), "input", texts));
		JsonNode data = response.path("data");
		if (!data.isArray() || data.size() != texts.size()) {
			throw new IllegalStateException("AI provider returned an invalid embedding count.");
		}
		List<List<Double>> result = new ArrayList<>();
		for (JsonNode item : data) {
			List<Double> vector = new ArrayList<>();
			item.path("embedding").forEach(value -> vector.add(value.asDouble()));
			if (vector.isEmpty()) {
				throw new IllegalStateException("AI provider returned an empty embedding.");
			}
			result.add(vector);
		}
		return result;
	}

	public String providerName() {
		return properties.provider().wireName();
	}

	public String embeddingModel() {
		return properties.embeddingModel();
	}

	private String profileModel() {
		return properties.profileModel();
	}

	private String reportModel() {
		return properties.reportModel();
	}

	private String baseUrl() {
		return properties.baseUrl();
	}

	/** {@link #postAllowingRejection}, where a refused request is as final as any other provider failure. */
	private JsonNode post(String url, Object payload) {
		try {
			return postAllowingRejection(url, payload);
		} catch (RejectedByProvider rejected) {
			throw providerFailure("request refused: " + rejected.getMessage());
		}
	}

	/**
	 * Sends one request. A 400 or 422 means the provider turned this request down, which only the meeting report
	 * path can retry differently, so it comes back as {@link RejectedByProvider}; every other failure is final.
	 */
	private JsonNode postAllowingRejection(String url, Object payload) {
		String apiKey = properties.apiKey();
		if (apiKey == null || apiKey.isBlank()) {
			throw providerFailure(providerName() + " API key is not configured");
		}
		HttpResponse<String> response;
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(90))
					.header("Authorization", "Bearer " + apiKey)
					.header("Content-Type", "application/json");
			if (properties.provider() == AiProvider.OPENROUTER) {
				builder.header("X-Title", properties.openrouter().appName());
			}
			response = httpClient.send(
					builder.POST(HttpRequest.BodyPublishers.ofString(jsonUtils.writeValue(payload))).build(),
					HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw providerFailure("request interrupted");
		} catch (IOException error) {
			throw providerFailure("unreachable (" + error.getClass().getSimpleName() + ")");
		}
		int status = response.statusCode();
		JsonNode body = readBody(response.body());
		if (status >= 200 && status < 300) {
			if (body == null) {
				throw providerFailure(
						"answered " + status + " with a body that is not JSON: " + excerpt(response.body()));
			}
			return body;
		}
		String detail = body == null
				? excerpt(response.body())
				: body.path("error").path("message").asString(body.path("detail").asString(excerpt(response.body())));
		if (status == 400 || status == 422) {
			throw new RejectedByProvider(status + " " + detail);
		}
		throw providerFailure("answered " + status + ": " + detail);
	}

	/** The parsed body, an empty object for an empty body, or null for one that is not JSON (a gateway's HTML). */
	private @Nullable JsonNode readBody(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return jsonUtils.createObjectNode();
		}
		try {
			return jsonUtils.readTree(body);
		} catch (IllegalStateException notJson) {
			return null;
		}
	}

	private static String excerpt(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return "(empty body)";
		}
		String flat = body.strip().replaceAll("\\s+", " ");
		return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
	}

	private ResponseStatusException providerFailure(String reason) {
		log.warn("AI provider call failed: provider={}, {}", providerName(), reason);
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY, Texts.forRequest("error.aiProviderUnavailable"));
	}

	/** The provider refused this particular request (400/422); its message is for the log, never for the user. */
	private static final class RejectedByProvider extends RuntimeException {
		RejectedByProvider(String detail) {
			super(detail);
		}
	}
}
