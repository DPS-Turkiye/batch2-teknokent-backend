package com.dps.teknokent.service;

import static com.dps.teknokent.util.JsonSchema.arrayOf;
import static com.dps.teknokent.util.JsonSchema.bool;
import static com.dps.teknokent.util.JsonSchema.nullableInteger;
import static com.dps.teknokent.util.JsonSchema.nullableString;
import static com.dps.teknokent.util.JsonSchema.object;
import static com.dps.teknokent.util.JsonSchema.property;
import static com.dps.teknokent.util.JsonSchema.string;
import static com.dps.teknokent.util.JsonSchema.stringArray;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.OcrMode;
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
import com.dps.teknokent.util.ModelOutput;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

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
	private final AiFeatures aiFeatures;

	public SyllabusPhasesResponse extract(UUID actorId, ExtractSyllabusPhasesRequest request) {
		AppUser actor = programService.requireCoordinatorOrAdmin(actorId);
		aiFeatures.require(AiFeature.SYLLABUS);
		StoredDocument document = requireReadable(actor, request.documentId());
		ExtractionResult extraction = textExtractor.extract(objectStorage.require(document.getS3Key()), OcrMode.AUTO);
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
				.filter(document -> document.getKind() == DocumentKind.SYLLABUS)
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
			String name = ModelOutput.text(node.path("name"), MAX_NAME_LENGTH);
			if (name == null) {
				continue;
			}
			LocalDate start = ModelOutput.date(node.path("start_date"));
			LocalDate end = ModelOutput.date(node.path("end_date"));
			if (start != null && end != null && end.isBefore(start)) {
				end = null;
			}
			phases.add(new SyllabusPhasesResponse.Phase(
					name, ModelOutput.text(node.path("expected_output"), MAX_OUTPUT_LENGTH), start, end));
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
			LocalDate date = ModelOutput.date(node.path("start_date"));
			if (number == null && date != null && programStart != null && !date.isBefore(programStart)) {
				number = (int) (ChronoUnit.DAYS.between(programStart, date) / 7) + 1;
			}
			String title = ModelOutput.text(node.path("title"), MAX_WEEK_TITLE_LENGTH);
			if (number == null || number < 1 || number > limit || title == null || weeks.containsKey(number)) {
				continue;
			}
			weeks.put(number, new ProgramWeekPlanResponse.Week(number,
					programStart == null ? null : ProgramCalendar.weekStart(programStart, number),
					programStart == null ? null : ProgramCalendar.weekEnd(programStart, programEnd, number),
					title, lines(node.path("topics")), lines(node.path("activities")),
					lines(node.path("deliverables")), node.path("inferred").asBoolean(false)));
		}
		return List.copyOf(weeks.values());
	}

	private static List<String> lines(JsonNode array) {
		return ModelOutput.lines(array, MAX_WEEK_LINES, MAX_OUTPUT_LENGTH, false);
	}

	private static JsonNode schema() {
		JsonNode phase = object(property("name", string()), property("expected_output", nullableString()),
				property("start_date", nullableString()), property("end_date", nullableString()));
		JsonNode week = object(property("week", nullableInteger()), property("start_date", nullableString()),
				property("title", string()), property("topics", stringArray()), property("activities", stringArray()),
				property("deliverables", stringArray()), property("inferred", bool()));
		return object(property("phases", arrayOf(phase)), property("weeks", arrayOf(week)),
				property("warnings", stringArray()));
	}
}
