package com.dps.teknokent.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.dps.teknokent.service.LocalPiiRedactionService.RedactionResult;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class NativeProfileParserService {
	/** The class years the student mentor form offers (frontend STUDENT_LEVELS); the parse answers with one. */
	static final List<String> STUDY_YEARS = List.of("Hazırlık", "1. sınıf", "2. sınıf", "3. sınıf", "4. sınıf",
			"Yüksek lisans", "Doktora");

	private final PdfDocumentTextExtractor textExtractor;
	private final LocalPiiRedactionService piiRedactionService;
	private final AiProviderClient aiProviderClient;
	private final JsonUtils jsonUtils;

	public NativeProfileParserService(PdfDocumentTextExtractor textExtractor,
			LocalPiiRedactionService piiRedactionService, AiProviderClient aiProviderClient, JsonUtils jsonUtils) {
		this.textExtractor = textExtractor;
		this.piiRedactionService = piiRedactionService;
		this.aiProviderClient = aiProviderClient;
		this.jsonUtils = jsonUtils;
	}

	public ParseResult parse(byte[] pdfBytes, String profileType, String ocrMode) {
		ExtractionResult extraction = textExtractor.extract(pdfBytes, ocrMode);
		RedactionResult redaction = piiRedactionService.redact(extraction.text());
		JsonNode profile = aiProviderClient.structuredProfile(profileType, redaction.text(), schema(profileType));

		ObjectNode redactionSummary = jsonUtils.createObjectNode();
		redactionSummary.put("mode", "local-before-ai");
		redactionSummary.put("total_redacted", redaction.totalRedacted());
		redactionSummary.set("redacted_by_type", jsonUtils.valueToTree(redaction.redactedByType()));

		ObjectNode ocrSummary = jsonUtils.createObjectNode();
		ocrSummary.put("mode", ocrMode);
		ocrSummary.put("used", extraction.ocrUsed());
		ocrSummary.put("page_count", extraction.pageCount());
		ocrSummary.set("ocr_pages", jsonUtils.valueToTree(extraction.ocrPages()));
		return new ParseResult(profile, redactionSummary, ocrSummary);
	}

	private JsonNode schema(String profileType) {
		return switch (profileType) {
			case "mentee" -> objectSchema(List.of(
					field("startup_name", nullableString()), field("startup_summary", nullableString()),
					field("stage", nullableString()), field("business_model", nullableString()),
					field("sectors", stringArray()), field("target_markets", stringArray()),
					field("team_size", nullableInteger()), field("technologies", stringArray()),
					field("current_bottlenecks", evidenceArray()), field("goals", evidenceArray()),
					field("support_needs", stringArray()), field("missing_fields", stringArray()),
					field("warnings", stringArray())));
			case "individual_mentee" -> objectSchema(List.of(
					field("full_name", nullableString()), field("professional_summary", nullableString()),
					field("current_role", nullableString()), field("desired_role", nullableString()),
					field("professional_functions", stringArray()), field("industries", stringArray()),
					field("experience_level", nullableString()), field("years_of_experience", nullableInteger()),
					field("technologies", stringArray()), field("projects", evidenceArray()),
					field("achievement_highlights", evidenceArray()), field("languages", stringArray()),
					field("career_goals", evidenceArray()), field("skills_to_develop", stringArray()),
					field("support_needs", stringArray()), field("missing_fields", stringArray()),
					field("warnings", stringArray())));
			case "mentor" -> objectSchema(List.of(
					field("full_name", nullableString()), field("professional_summary", nullableString()),
					field("current_role", nullableString()), field("current_company", nullableString()),
					field("experience_level", nullableString()), field("years_of_experience", nullableInteger()),
					field("professional_functions", stringArray()), field("industries", stringArray()),
					field("technologies", stringArray()), field("languages", stringArray()),
					field("work_history", arrayOf(objectSchema(List.of(
							field("title", string()), field("organization", nullableString()),
							field("period", nullableString()), field("summary", nullableString()))))),
					// Student mentors (İŞKUR) fill the studies card from these; professionals get null and [].
					field("department", described(nullableString(),
							"University department or major, only if the person is currently a student")),
					field("study_year", described(nullableString(),
							"Current class year of a student, exactly one of: " + String.join(", ", STUDY_YEARS))),
					field("strong_courses", described(stringArray(),
							"Courses the person lists as strong or did well in, as written, e.g. CENG 140")),
					field("student_projects", described(stringArray(),
							"Short titles of academic, personal or competition projects")),
					field("exchange_experience", described(nullableString(),
							"Erasmus, exchange or study abroad: school and term, e.g. TU Delft, Fall 2025")),
					field("missing_fields", stringArray()), field("warnings", stringArray())));
			default -> throw new IllegalArgumentException("Unsupported profile type: " + profileType);
		};
	}

	private JsonNode evidenceArray() {
		return arrayOf(objectSchema(List.of(
				field("claim", string()), field("page", nullableInteger()), field("quote", string()),
				field("information_type", enumString("explicit", "inferred")))));
	}

	private JsonNode objectSchema(List<Field> fields) {
		ObjectNode schema = jsonUtils.createObjectNode();
		schema.put("type", "object");
		schema.put("additionalProperties", false);
		ObjectNode properties = schema.putObject("properties");
		ArrayNode required = schema.putArray("required");
		fields.forEach(field -> {
			properties.set(field.name(), field.schema());
			required.add(field.name());
		});
		return schema;
	}

	private JsonNode string() {
		return jsonUtils.createObjectNode().put("type", "string");
	}

	private JsonNode nullableString() {
		return typeUnion("string", "null");
	}

	private JsonNode nullableInteger() {
		return typeUnion("integer", "null");
	}

	private JsonNode stringArray() {
		return arrayOf(string());
	}

	private JsonNode arrayOf(JsonNode itemSchema) {
		ObjectNode schema = jsonUtils.createObjectNode().put("type", "array");
		schema.set("items", itemSchema);
		return schema;
	}

	private JsonNode enumString(String... values) {
		ObjectNode schema = jsonUtils.createObjectNode().put("type", "string");
		ArrayNode allowed = schema.putArray("enum");
		for (String value : values) allowed.add(value);
		return schema;
	}

	private JsonNode described(JsonNode schema, String description) {
		return ((ObjectNode) schema).put("description", description);
	}

	private JsonNode typeUnion(String... types) {
		ObjectNode schema = jsonUtils.createObjectNode();
		ArrayNode values = schema.putArray("type");
		for (String type : types) values.add(type);
		return schema;
	}

	private Field field(String name, JsonNode schema) {
		return new Field(name, schema);
	}

	private record Field(String name, JsonNode schema) {}
	public record ParseResult(JsonNode profile, JsonNode redactionSummary, JsonNode ocrSummary) {}
}
