package com.dps.teknokent.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.WeeklyProgramWarning;
import com.dps.teknokent.dto.requests.ReadWeeklyProgramRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.requests.SaveWeeklyProgramRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.dto.responses.WeeklyProgramProposalResponse;
import com.dps.teknokent.dto.responses.WeeklyProgramSavedResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * A program's weekly program PDF: read into weeks and the phases they form, reviewed by a coordinator, then saved
 * in one step. The same local redaction as CV parsing runs before the AI call, and nothing is saved by a read.
 */
@Service
@RequiredArgsConstructor
public class WeeklyProgramService {

	private static final int MAX_WEEK_TITLE_LENGTH = 160;
	private static final int MAX_WEEK_LINES = 8;
	private static final int MAX_LINE_LENGTH = 200;
	private static final int MAX_PHASE_NAME_LENGTH = 120;
	private static final int MAX_NOTES = 10;
	// A weekly program is a page or two; anything longer is cut, not chunked.
	static final int MAX_TEXT_LENGTH = 60_000;

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramWeekPlanService programWeekPlanService;
	private final ProgramPhaseRepository programPhaseRepository;
	private final ProgramWeekRepository programWeekRepository;
	private final StoredDocumentRepository storedDocumentRepository;
	private final ObjectStorage objectStorage;
	private final PdfDocumentTextExtractor textExtractor;
	private final LocalPiiRedactionService piiRedactionService;
	private final AiProviderClient aiProviderClient;
	private final JsonUtils jsonUtils;
	private final AiFeatures aiFeatures;

	public WeeklyProgramProposalResponse read(UUID actorId, UUID programId, ReadWeeklyProgramRequest request) {
		Program program = coordinatorProgramService.requireSettingsEditor(actorId, programId);
		// The same switch as the syllabus read: no file is read and nothing reaches the AI while it is off.
		aiFeatures.require(AiFeature.SYLLABUS);
		int totalWeeks = requireDates(program);
		StoredDocument document = requireReadable(actorId, program, request.documentId());
		ExtractionResult extraction = textExtractor.extract(objectStorage.get(document.getS3Key()), "auto");
		String text = piiRedactionService.redact(extraction.text()).text();
		if (text.isBlank()) {
			throw new InvalidRequestException("No text could be read from the weekly program.");
		}
		List<WeeklyProgramProposalResponse.Warning> warnings = new ArrayList<>();
		if (text.length() > MAX_TEXT_LENGTH) {
			text = text.substring(0, MAX_TEXT_LENGTH);
			warnings.add(new WeeklyProgramProposalResponse.Warning(WeeklyProgramWarning.TEXT_CUT, List.of()));
		}
		JsonNode result = aiProviderClient.weeklyProgram(text, program.getStartDate(), program.getEndDate(), schema());
		WeeklyProgramProposalResponse proposal = proposalOf(result, program.getStartDate(), program.getEndDate(),
				totalWeeks, warnings, extraction.pageCount());
		return withExisting(proposal, program, today());
	}

	@Transactional
	public WeeklyProgramSavedResponse save(UUID actorId, UUID programId, SaveWeeklyProgramRequest request) {
		Program program = coordinatorProgramService.requireSettingsEditor(actorId, programId);
		int totalWeeks = requireDates(program);
		requireOrderedPhases(request.phases(), totalWeeks);
		List<ProgramPhase> savedPhases = programPhaseRepository.findByProgramIdOrderByPositionAsc(programId);
		List<ProgramWeek> savedWeeks = programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId);
		if (savedPhases.size() != request.seenPhaseCount() || savedWeeks.size() != request.seenWeekCount()) {
			throw new ConflictException("The program's plan changed since the file was read; read it again.");
		}
		LocalDate today = today();
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		List<ReplaceProgramPhasesRequest.PhaseInput> phases = request.phases().stream()
				.map(phase -> new ReplaceProgramPhasesRequest.PhaseInput(phase.name().trim(),
						ProgramCalendar.weekStart(start, phase.firstWeek()),
						ProgramCalendar.weekEnd(start, end, phase.lastWeek()), phase.expectedOutput()))
				.toList();
		List<ReplaceProgramWeeksRequest.WeekInput> weeks = request.weeks();
		// After the end every week is past, so the last one is kept as well.
		Integer currentWeek = today.isAfter(end)
				? Integer.valueOf(totalWeeks + 1)
				: ProgramCalendar.currentWeek(start, end, today);
		if (request.keepPast() && currentWeek != null) {
			phases = keepCompletedPhases(savedPhases, phases, today);
			weeks = keepPastWeeks(savedWeeks, weeks, currentWeek);
		}
		if (phases.size() > WeeklyProgramPhases.MAX_PHASES) {
			throw new InvalidRequestException("A program has at most " + WeeklyProgramPhases.MAX_PHASES + " phases.");
		}
		requireReadable(actorId, program, request.documentId());
		coordinatorProgramService.writePhases(programId, phases);
		ProgramWeekPlanResponse plan = programWeekPlanService.writeWeeks(program, actorId, weeks);
		coordinatorProgramService.attachSyllabus(program, actorId, request.documentId());
		return new WeeklyProgramSavedResponse(coordinatorProgramService.detail(actorId, programId), plan);
	}

	/** Weeks and phases are only placed on a calendar, so the program needs both dates first. */
	private static int requireDates(Program program) {
		Integer total = ProgramCalendar.totalWeeks(program.getStartDate(), program.getEndDate());
		if (total == null) {
			throw new InvalidRequestException("Set the program's start and end dates before reading a weekly program.");
		}
		return total;
	}

	private static void requireOrderedPhases(List<SaveWeeklyProgramRequest.PhaseInput> phases, int totalWeeks) {
		int previousLast = 0;
		for (SaveWeeklyProgramRequest.PhaseInput phase : phases) {
			if (phase.lastWeek() < phase.firstWeek() || phase.firstWeek() <= previousLast) {
				throw new InvalidRequestException("Phases must follow each other without overlapping.");
			}
			if (phase.lastWeek() > totalWeeks) {
				throw new InvalidRequestException("The program has only " + totalWeeks + " weeks.");
			}
			previousLast = phase.lastWeek();
		}
	}

	/** A weekly program the actor uploaded, or the one already on the program. Anything else reads as missing. */
	private StoredDocument requireReadable(UUID actorId, Program program, UUID documentId) {
		return storedDocumentRepository.findById(documentId)
				.filter(document -> DocumentKind.WEEKLY_PROGRAM.value().equals(document.getKind()))
				.filter(document -> actorId.equals(document.getUserId())
						|| document.getId().equals(program.getSyllabusDocumentId()))
				.orElseThrow(() -> new ResourceNotFoundException("Weekly program not found: " + documentId));
	}

	/**
	 * Completed phases (ended before today) stay as saved. New phases that ended before today are dropped, and one
	 * that runs into today starts after the last kept phase.
	 */
	static List<ReplaceProgramPhasesRequest.PhaseInput> keepCompletedPhases(List<ProgramPhase> saved,
			List<ReplaceProgramPhasesRequest.PhaseInput> proposed, LocalDate today) {
		List<ReplaceProgramPhasesRequest.PhaseInput> phases = new ArrayList<>();
		LocalDate keptUntil = null;
		for (ProgramPhase phase : saved) {
			if (phase.getEndDate() != null && phase.getEndDate().isBefore(today)) {
				phases.add(new ReplaceProgramPhasesRequest.PhaseInput(phase.getName(), phase.getStartDate(),
						phase.getEndDate(), phase.getExpectedOutput()));
				keptUntil = keptUntil == null || phase.getEndDate().isAfter(keptUntil) ? phase.getEndDate() : keptUntil;
			}
		}
		for (ReplaceProgramPhasesRequest.PhaseInput phase : proposed) {
			if (phase.endDate().isBefore(today)) {
				continue;
			}
			LocalDate start = keptUntil != null && !phase.startDate().isAfter(keptUntil)
					? keptUntil.plusDays(1)
					: phase.startDate();
			if (start.isAfter(phase.endDate())) {
				continue;
			}
			phases.add(new ReplaceProgramPhasesRequest.PhaseInput(phase.name(), start, phase.endDate(),
					phase.expectedOutput()));
		}
		return phases;
	}

	/** Saved weeks before the current one stay; the new plan fills the current week and those after it. */
	static List<ReplaceProgramWeeksRequest.WeekInput> keepPastWeeks(List<ProgramWeek> saved,
			List<ReplaceProgramWeeksRequest.WeekInput> proposed, int currentWeek) {
		List<ReplaceProgramWeeksRequest.WeekInput> weeks = new ArrayList<>();
		for (ProgramWeek week : saved) {
			if (week.getWeekNumber() < currentWeek) {
				weeks.add(new ReplaceProgramWeeksRequest.WeekInput(week.getWeekNumber(), week.getTitle(),
						ProgramWeek.lines(week.getTopics()), ProgramWeek.lines(week.getActivities()),
						ProgramWeek.lines(week.getDeliverables()), week.isInferred()));
			}
		}
		proposed.stream().filter(week -> week.week() >= currentWeek).forEach(weeks::add);
		return weeks;
	}

	private WeeklyProgramProposalResponse withExisting(WeeklyProgramProposalResponse proposal, Program program,
			LocalDate today) {
		UUID programId = program.getId();
		return new WeeklyProgramProposalResponse(proposal.totalWeeks(), proposal.weeks(), proposal.phases(),
				proposal.warnings(), proposal.notes(), proposal.readingWarnings(), proposal.pageCount(),
				new WeeklyProgramProposalResponse.Existing(
						programPhaseRepository.findByProgramIdOrderByPositionAsc(programId).size(),
						programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId).size(),
						ProgramCalendar.currentWeek(program.getStartDate(), program.getEndDate(), today)));
	}

	/**
	 * The AI's answer on the program calendar. Week numbers win over printed dates (a printed date only places a
	 * week that has no number, and one that falls outside its week is flagged); weeks past the program or without a
	 * title are dropped and flagged, and a repeated week keeps its first entry.
	 */
	static WeeklyProgramProposalResponse proposalOf(JsonNode result, LocalDate start, LocalDate end, int totalWeeks,
			List<WeeklyProgramProposalResponse.Warning> warnings, int pageCount) {
		TreeMap<Integer, ProgramWeekPlanResponse.Week> weeks = new TreeMap<>();
		SortedMap<Integer, String> weekPhases = new TreeMap<>();
		Set<Integer> dropped = new LinkedHashSet<>();
		Set<Integer> datesDiffer = new LinkedHashSet<>();
		for (JsonNode node : result.path("weeks")) {
			LocalDate printed = dateOf(node.path("source_date"));
			boolean numbered = isWeekNumber(node.path("week"));
			Integer number = numbered ? node.path("week").asInt() : null;
			if (number == null && printed != null && !printed.isBefore(start)) {
				number = (int) (ChronoUnit.DAYS.between(start, printed) / 7) + 1;
			}
			String title = cut(node.path("title").asText(""), MAX_WEEK_TITLE_LENGTH);
			if (number == null) {
				continue;
			}
			if (weeks.containsKey(number)) {
				continue;
			}
			if (number < 1 || number > totalWeeks || title == null) {
				dropped.add(number);
				continue;
			}
			LocalDate weekStart = ProgramCalendar.weekStart(start, number);
			LocalDate weekEnd = ProgramCalendar.weekEnd(start, end, number);
			if (numbered && printed != null && (printed.isBefore(weekStart) || printed.isAfter(weekStart.plusDays(6)))) {
				datesDiffer.add(number);
			}
			weeks.put(number, new ProgramWeekPlanResponse.Week(number, weekStart, weekEnd, title,
					linesOf(node.path("topics")), linesOf(node.path("activities")),
					linesOf(node.path("deliverables")), !numbered));
			weekPhases.put(number, cut(node.path("phase").asText(""), MAX_PHASE_NAME_LENGTH));
		}
		WeeklyProgramPhases.Result grouped = WeeklyProgramPhases.group(rangesOf(result, totalWeeks), weekPhases);
		List<WeeklyProgramProposalResponse.Warning> all = new ArrayList<>(warnings);
		addWarning(all, WeeklyProgramWarning.DATES_DIFFER, datesDiffer, false);
		addWarning(all, WeeklyProgramWarning.WEEKS_DROPPED, dropped, false);
		addWarning(all, WeeklyProgramWarning.PHASES_REBUILT, Set.of(), grouped.rebuilt());
		addWarning(all, WeeklyProgramWarning.PHASES_MERGED, Set.of(), grouped.merged());
		addWarning(all, WeeklyProgramWarning.NO_PHASES, Set.of(), !weeks.isEmpty() && grouped.phases().isEmpty());
		List<WeeklyProgramProposalResponse.Phase> phases = grouped.phases().stream()
				.map(range -> new WeeklyProgramProposalResponse.Phase(range.name(), range.expectedOutput(),
						range.firstWeek(), range.lastWeek(), ProgramCalendar.weekStart(start, range.firstWeek()),
						ProgramCalendar.weekEnd(start, end, range.lastWeek())))
				.toList();
		return new WeeklyProgramProposalResponse(totalWeeks, List.copyOf(weeks.values()), phases, all,
				linesOf(result.path("notes"), MAX_NOTES), linesOf(result.path("warnings"), MAX_NOTES), pageCount, null);
	}

	/** The AI's phase ranges, cut to the program; a range wholly past the program is left out. */
	private static List<WeeklyProgramPhases.Range> rangesOf(JsonNode result, int totalWeeks) {
		List<WeeklyProgramPhases.Range> ranges = new ArrayList<>();
		for (JsonNode node : result.path("phases")) {
			String name = cut(node.path("name").asText(""), MAX_PHASE_NAME_LENGTH);
			if (name == null || !isWeekNumber(node.path("first_week")) || !isWeekNumber(node.path("last_week"))) {
				continue;
			}
			int first = node.path("first_week").asInt();
			int last = Math.min(node.path("last_week").asInt(), totalWeeks);
			if (first > totalWeeks) {
				continue;
			}
			ranges.add(new WeeklyProgramPhases.Range(name, cut(node.path("expected_output").asText(""),
					MAX_LINE_LENGTH), first, last));
		}
		return ranges;
	}

	private static boolean isWeekNumber(JsonNode node) {
		return node.isIntegralNumber() && node.canConvertToInt();
	}

	private static void addWarning(List<WeeklyProgramProposalResponse.Warning> warnings, WeeklyProgramWarning code,
			Set<Integer> weeks, boolean always) {
		if (always || !weeks.isEmpty()) {
			warnings.add(new WeeklyProgramProposalResponse.Warning(code, List.copyOf(weeks)));
		}
	}

	private static List<String> linesOf(JsonNode array) {
		return linesOf(array, MAX_WEEK_LINES);
	}

	private static List<String> linesOf(JsonNode array, int max) {
		List<String> lines = new ArrayList<>();
		for (JsonNode item : array) {
			String line = cut(item.asText(""), MAX_LINE_LENGTH);
			if (line != null && !isPlaceholder(line) && !lines.contains(line)) {
				lines.add(line);
			}
			if (lines.size() == max) {
				break;
			}
		}
		return lines;
	}

	/** Table cells such as "–" mean "nothing this week". */
	private static boolean isPlaceholder(String line) {
		return line.chars().noneMatch(Character::isLetterOrDigit);
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

	private static LocalDate today() {
		return LocalDate.now(MeetingSlotTimes.APP_ZONE);
	}

	private JsonNode schema() {
		ObjectNode week = object();
		ObjectNode weekProperties = week.putObject("properties");
		weekProperties.set("week", nullable("integer"));
		weekProperties.set("source_date", nullable("string"));
		weekProperties.set("title", jsonUtils.createObjectNode().put("type", "string"));
		for (String list : List.of("topics", "activities", "deliverables")) {
			stringArray(weekProperties, list);
		}
		weekProperties.set("phase", nullable("string"));
		require(week, "week", "source_date", "title", "topics", "activities", "deliverables", "phase");

		ObjectNode phase = object();
		ObjectNode phaseProperties = phase.putObject("properties");
		phaseProperties.set("name", jsonUtils.createObjectNode().put("type", "string"));
		phaseProperties.set("expected_output", nullable("string"));
		phaseProperties.set("first_week", jsonUtils.createObjectNode().put("type", "integer"));
		phaseProperties.set("last_week", jsonUtils.createObjectNode().put("type", "integer"));
		require(phase, "name", "expected_output", "first_week", "last_week");

		ObjectNode root = object();
		ObjectNode properties = root.putObject("properties");
		properties.putObject("weeks").put("type", "array").set("items", week);
		properties.putObject("phases").put("type", "array").set("items", phase);
		stringArray(properties, "notes");
		stringArray(properties, "warnings");
		require(root, "weeks", "phases", "notes", "warnings");
		return root;
	}

	private void stringArray(ObjectNode properties, String name) {
		properties.putObject(name).put("type", "array").putObject("items").put("type", "string");
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
