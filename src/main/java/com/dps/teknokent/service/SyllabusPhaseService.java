package com.dps.teknokent.service;

import com.dps.teknokent.domain.AiFeature;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ExtractSyllabusPhasesRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.dto.responses.SyllabusPhasesResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.ProgramCoordination;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * Reads a syllabus PDF and proposes program phases and a week-by-week plan; the same local redaction as CV parsing
 * runs first.
 */
@Service
@RequiredArgsConstructor
public class SyllabusPhaseService {

	static final int MAX_PHASES = 12;
	static final int MAX_WEEKS = 104;
	private static final int MAX_WEEK_TITLE_LENGTH = 160;
	private static final int MAX_WEEK_LINES = 8;
	private static final int MAX_NAME_LENGTH = 120;
	private static final int MAX_OUTPUT_LENGTH = 200;
	// ponytail: a long syllabus is cut, not chunked; split it into parts if real files hit this.
	static final int MAX_TEXT_LENGTH = 60_000;

	private final CoordinatorProgramService programService;
	private final StoredDocumentRepository storedDocumentRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ObjectStorage objectStorage;
	private final PdfDocumentTextExtractor textExtractor;
	private final LocalPiiRedactionService piiRedactionService;
	private final AiProviderClient aiProviderClient;
	private final JsonUtils jsonUtils;
	private final AiFeatures aiFeatures;

	public SyllabusPhasesResponse extract(UUID actorId, ExtractSyllabusPhasesRequest request) {
		AppUser actor = programService.requireCoordinatorOrAdmin(actorId);
		aiFeatures.require(AiFeature.SYLLABUS);
		StoredDocument document = requireReadable(actor, request.documentId());
		ExtractionResult extraction = textExtractor.extract(objectStorage.get(document.getS3Key()), "auto");
		String text = piiRedactionService.redact(extraction.text()).text();
		if (text.isBlank()) {
			throw new InvalidRequestException("No text could be read from the syllabus.");
		}
		if (text.length() > MAX_TEXT_LENGTH) {
			text = text.substring(0, MAX_TEXT_LENGTH);
		}
		JsonNode result = aiProviderClient.syllabusPhases(text, request.startDate(), request.endDate(), schema());
		return new SyllabusPhasesResponse(phasesOf(result), weeksOf(result, request.startDate(), request.endDate()),
				extraction.pageCount());
	}

	/** The actor's own upload, or the syllabus of a program they run. Anything else reads as missing. */
	private StoredDocument requireReadable(AppUser actor, UUID documentId) {
		return storedDocumentRepository.findById(documentId)
				.filter(document -> DocumentKind.SYLLABUS.value().equals(document.getKind()))
				.filter(document -> actor.getId().equals(document.getUserId()) || runsProgramWith(actor, documentId))
				.orElseThrow(() -> new ResourceNotFoundException("Syllabus not found: " + documentId));
	}

	private boolean runsProgramWith(AppUser actor, UUID documentId) {
		return programRepository.findBySyllabusDocumentId(documentId).stream()
				.anyMatch(program -> actor.has(UserRole.ADMIN)
						|| ProgramCoordination.coordinates(program, actor.getId(), programCoordinatorRepository));
	}

	static List<SyllabusPhasesResponse.Phase> phasesOf(JsonNode result) {
		List<SyllabusPhasesResponse.Phase> phases = new ArrayList<>();
		for (JsonNode node : result.path("phases")) {
			String name = cut(node.path("name").asText(""), MAX_NAME_LENGTH);
			if (name == null) {
				continue;
			}
			LocalDate start = dateOf(node.path("start_date"));
			LocalDate end = dateOf(node.path("end_date"));
			if (start != null && end != null && end.isBefore(start)) {
				end = null;
			}
			phases.add(new SyllabusPhasesResponse.Phase(
					name, cut(node.path("expected_output").asText(""), MAX_OUTPUT_LENGTH), start, end));
			if (phases.size() == MAX_PHASES) {
				break;
			}
		}
		return phases;
	}

	/**
	 * The AI's weeks, keyed by week number: a week given only by its start date is placed by the program start;
	 * weeks outside the program, without a title, or repeated are dropped. Dates always come from ProgramCalendar.
	 */
	static List<ProgramWeekPlanResponse.Week> weeksOf(JsonNode result, LocalDate programStart, LocalDate programEnd) {
		Integer total = ProgramCalendar.totalWeeks(programStart, programEnd);
		int limit = total == null ? MAX_WEEKS : Math.min(total, MAX_WEEKS);
		TreeMap<Integer, ProgramWeekPlanResponse.Week> weeks = new TreeMap<>();
		for (JsonNode node : result.path("weeks")) {
			Integer number = node.path("week").canConvertToInt() && node.path("week").isNumber()
					? node.path("week").asInt()
					: null;
			LocalDate date = dateOf(node.path("start_date"));
			if (number == null && date != null && programStart != null && !date.isBefore(programStart)) {
				number = (int) (ChronoUnit.DAYS.between(programStart, date) / 7) + 1;
			}
			String title = cut(node.path("title").asText(""), MAX_WEEK_TITLE_LENGTH);
			if (number == null || number < 1 || number > limit || title == null || weeks.containsKey(number)) {
				continue;
			}
			weeks.put(number, new ProgramWeekPlanResponse.Week(number,
					programStart == null ? null : ProgramCalendar.weekStart(programStart, number),
					programStart == null ? null : ProgramCalendar.weekEnd(programStart, programEnd, number),
					title, linesOf(node.path("topics")), linesOf(node.path("activities")),
					linesOf(node.path("deliverables")), node.path("inferred").asBoolean(false)));
		}
		return List.copyOf(weeks.values());
	}

	private static List<String> linesOf(JsonNode array) {
		List<String> lines = new ArrayList<>();
		for (JsonNode item : array) {
			String line = cut(item.asText(""), MAX_OUTPUT_LENGTH);
			if (line != null && !lines.contains(line)) {
				lines.add(line);
			}
			if (lines.size() == MAX_WEEK_LINES) {
				break;
			}
		}
		return lines;
	}

	private static String cut(String value, int maxLength) {
		String trimmed = value.trim();
		if (trimmed.isEmpty() || "null".equals(trimmed)) {
			return null;
		}
		return trimmed.length() > maxLength ? trimmed.substring(0, maxLength).trim() : trimmed;
	}

	private static LocalDate dateOf(JsonNode node) {
		if (!node.isTextual()) {
			return null;
		}
		try {
			return LocalDate.parse(node.asText().trim());
		} catch (DateTimeParseException unreadable) {
			return null;
		}
	}

	private JsonNode schema() {
		ObjectNode phase = object();
		ObjectNode phaseProperties = phase.putObject("properties");
		phaseProperties.set("name", jsonUtils.createObjectNode().put("type", "string"));
		phaseProperties.set("expected_output", nullable("string"));
		phaseProperties.set("start_date", nullable("string"));
		phaseProperties.set("end_date", nullable("string"));
		require(phase, "name", "expected_output", "start_date", "end_date");

		ObjectNode week = object();
		ObjectNode weekProperties = week.putObject("properties");
		weekProperties.set("week", nullable("integer"));
		weekProperties.set("start_date", nullable("string"));
		weekProperties.set("title", jsonUtils.createObjectNode().put("type", "string"));
		for (String list : List.of("topics", "activities", "deliverables")) {
			weekProperties.putObject(list).put("type", "array").putObject("items").put("type", "string");
		}
		weekProperties.set("inferred", jsonUtils.createObjectNode().put("type", "boolean"));
		require(week, "week", "start_date", "title", "topics", "activities", "deliverables", "inferred");

		ObjectNode root = object();
		ObjectNode properties = root.putObject("properties");
		properties.putObject("phases").put("type", "array").set("items", phase);
		properties.putObject("weeks").put("type", "array").set("items", week);
		properties.putObject("warnings").put("type", "array").putObject("items").put("type", "string");
		require(root, "phases", "weeks", "warnings");
		return root;
	}

	private ObjectNode object() {
		return jsonUtils.createObjectNode().put("type", "object").put("additionalProperties", false);
	}

	private ObjectNode nullable(String type) {
		ObjectNode schema = jsonUtils.createObjectNode();
		schema.putArray("type").add(type).add("null");
		return schema;
	}

	private static void require(ObjectNode schema, String... names) {
		ArrayNode required = schema.putArray("required");
		for (String name : names) {
			required.add(name);
		}
	}
}
