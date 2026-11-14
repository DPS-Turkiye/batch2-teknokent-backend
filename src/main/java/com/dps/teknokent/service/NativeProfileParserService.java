package com.dps.teknokent.service;

import static com.dps.teknokent.util.JsonSchema.arrayOf;
import static com.dps.teknokent.util.JsonSchema.described;
import static com.dps.teknokent.util.JsonSchema.enumOf;
import static com.dps.teknokent.util.JsonSchema.nullableInteger;
import static com.dps.teknokent.util.JsonSchema.nullableString;
import static com.dps.teknokent.util.JsonSchema.object;
import static com.dps.teknokent.util.JsonSchema.property;
import static com.dps.teknokent.util.JsonSchema.string;
import static com.dps.teknokent.util.JsonSchema.stringArray;

import java.util.List;

import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.OcrMode;
import com.dps.teknokent.domain.ProfileType;
import com.dps.teknokent.service.LocalPiiRedactionService.RedactionResult;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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

	public ParseResult parse(byte[] pdfBytes, ProfileType profileType, OcrMode ocrMode) {
		ExtractionResult extraction = textExtractor.extract(pdfBytes, ocrMode);
		RedactionResult redaction = piiRedactionService.redact(extraction.text());
		JsonNode profile = aiProviderClient.structuredProfile(profileType.value(), redaction.text(),
				schema(profileType));

		ObjectNode redactionSummary = jsonUtils.createObjectNode();
		redactionSummary.put("mode", "local-before-ai");
		redactionSummary.put("total_redacted", redaction.totalRedacted());
		redactionSummary.set("redacted_by_type", jsonUtils.valueToTree(redaction.redactedByType()));

		ObjectNode ocrSummary = jsonUtils.createObjectNode();
		ocrSummary.put("mode", ocrMode.wireValue());
		ocrSummary.put("used", extraction.ocrUsed());
		ocrSummary.put("page_count", extraction.pageCount());
		ocrSummary.set("ocr_pages", jsonUtils.valueToTree(extraction.ocrPages()));
		return new ParseResult(profile, redactionSummary, ocrSummary);
	}

	private static JsonNode schema(ProfileType profileType) {
		return switch (profileType) {
		case MENTEE -> object(
				property("startup_name", nullableString()), property("startup_summary", nullableString()),
				property("stage", nullableString()), property("business_model", nullableString()),
				property("sectors", stringArray()), property("target_markets", stringArray()),
				property("team_size", nullableInteger()), property("technologies", stringArray()),
				property("current_bottlenecks", evidenceArray()), property("goals", evidenceArray()),
				property("support_needs", stringArray()), property("missing_fields", stringArray()),
				property("warnings", stringArray()));
		case INDIVIDUAL_MENTEE -> object(
				property("full_name", nullableString()), property("professional_summary", nullableString()),
				property("current_role", nullableString()), property("desired_role", nullableString()),
				property("professional_functions", stringArray()), property("industries", stringArray()),
				property("experience_level", nullableString()), property("years_of_experience", nullableInteger()),
				property("technologies", stringArray()), property("projects", evidenceArray()),
				property("achievement_highlights", evidenceArray()), property("languages", stringArray()),
				property("career_goals", evidenceArray()), property("skills_to_develop", stringArray()),
				property("support_needs", stringArray()), property("missing_fields", stringArray()),
				property("warnings", stringArray()));
		case MENTOR -> object(
				property("full_name", nullableString()), property("professional_summary", nullableString()),
				property("current_role", nullableString()), property("current_company", nullableString()),
				property("experience_level", nullableString()), property("years_of_experience", nullableInteger()),
				property("professional_functions", stringArray()), property("industries", stringArray()),
				property("technologies", stringArray()), property("languages", stringArray()),
				property("work_history", described(arrayOf(object(
						property("title", string()), property("organization", nullableString()),
						property("period", nullableString()), property("summary", nullableString()))),
						"Every role the person held, including internships, part-time jobs, student club or "
								+ "society roles and volunteer work, newest first")),
				property("education", described(arrayOf(object(
						property("school", string()), property("degree", nullableString()),
						property("period", nullableString()))),
						"Schools, newest first. degree holds the level and department, e.g. "
								+ "\"Lisans, Bilgisayar Mühendisliği\", or \"Erasmus\" for an exchange "
								+ "semester; period holds only the years or term, e.g. \"2022 – 2026\" or "
								+ "\"2025 Güz\". Include exchange semesters and high school only if the CV "
								+ "lists them")),
				// Student mentors (İŞKUR) fill the studies card from these; professionals get null and [].
				property("department", described(nullableString(),
						"University department or major, only if the person is currently a student")),
				property("study_year", described(nullableString(),
						"Current class year of a student, exactly one of: " + String.join(", ", STUDY_YEARS))),
				property("strong_courses", described(stringArray(),
						"Courses the person lists as strong or did well in, as written, e.g. CENG 140")),
				property("student_projects", described(stringArray(),
						"Short titles of academic, personal or competition projects")),
				property("exchange_experience", described(nullableString(),
						"Erasmus, exchange or study abroad: school and term, e.g. TU Delft, Fall 2025")),
				property("missing_fields", stringArray()), property("warnings", stringArray()));
		default -> throw new IllegalArgumentException("Unsupported profile type: " + profileType);
		};
	}

	private static JsonNode evidenceArray() {
		return arrayOf(object(
				property("claim", string()), property("page", nullableInteger()), property("quote", string()),
				property("information_type", enumOf("explicit", "inferred"))));
	}

	public record ParseResult(JsonNode profile, JsonNode redactionSummary, JsonNode ocrSummary) {
	}
}
