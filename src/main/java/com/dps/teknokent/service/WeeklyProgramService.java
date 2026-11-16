package com.dps.teknokent.service;

import static com.dps.teknokent.util.JsonSchema.arrayOf;
import static com.dps.teknokent.util.JsonSchema.integer;
import static com.dps.teknokent.util.JsonSchema.nullableInteger;
import static com.dps.teknokent.util.JsonSchema.nullableString;
import static com.dps.teknokent.util.JsonSchema.object;
import static com.dps.teknokent.util.JsonSchema.property;
import static com.dps.teknokent.util.JsonSchema.string;
import static com.dps.teknokent.util.JsonSchema.stringArray;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.OcrMode;
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
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ModelOutput;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.ProgramSchedule;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * A program's weekly program PDF: read into weeks and the phases they form, reviewed by a coordinator, then saved
 * in one step. The same local redaction as CV parsing runs before the AI call, and nothing is saved by a read.
 */
@Service
@RequiredArgsConstructor
public class WeeklyProgramService {

	private static final int MAX_WEEK_TITLE_LENGTH = 160;
	private static final int MAX_WEEK_LINES = 12;
	private static final int MAX_LINE_LENGTH = 200;
	private static final int MAX_PHASE_NAME_LENGTH = 120;
	private static final int MAX_NOTES = 10;
	// A weekly program is a page or two; anything longer is cut, not chunked.
	static final int MAX_TEXT_LENGTH = 60_000;
	private static final DateTimeFormatter NOTE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramWeekPlanService programWeekPlanService;
	private final ProgramPhaseRepository programPhaseRepository;
	private final ProgramWeekRepository programWeekRepository;
	private final StoredDocumentRepository storedDocumentRepository;
	private final ObjectStorage objectStorage;
	private final PdfDocumentTextExtractor textExtractor;
	private final LocalPiiRedactionService piiRedactionService;
	private final AiProviderClient aiProviderClient;
	private final AiFeatures aiFeatures;

	public WeeklyProgramProposalResponse read(UUID actorId, UUID programId, ReadWeeklyProgramRequest request) {
		Program program = coordinatorProgramService.requireSettingsEditor(actorId, programId);
		// The same switch as the syllabus read: no file is read and nothing reaches the AI while it is off.
		aiFeatures.require(AiFeature.SYLLABUS);
		int totalWeeks = requireDates(program);
		StoredDocument document = requireReadable(actorId, program, request.documentId());
		ExtractionResult extraction = textExtractor.extract(objectStorage.require(document.getS3Key()), OcrMode.AUTO);
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
		int totalWeeks = Math.max(requireDates(program),
				request.weeks().stream().mapToInt(ReplaceProgramWeeksRequest.WeekInput::week).max().orElse(0));
		requireOrderedPhases(request.phases(), totalWeeks);
		List<ProgramPhase> savedPhases = programPhaseRepository.findByProgramIdOrderByPositionAsc(programId);
		List<ProgramWeek> savedWeeks = programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId);
		if (savedPhases.size() != request.seenPhaseCount() || savedWeeks.size() != request.seenWeekCount()) {
			throw new ConflictException("The program's plan changed since the file was read; read it again.");
		}
		LocalDate today = today();
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		Map<String, UUID> savedIds = idsByName(savedPhases);
		List<ReplaceProgramPhasesRequest.PhaseInput> phases = request.phases().stream()
				.map(phase -> new ReplaceProgramPhasesRequest.PhaseInput(phase.name().trim(),
						request.weeks().stream().filter(week -> week.week() == phase.firstWeek())
								.map(ReplaceProgramWeeksRequest.WeekInput::startDate).filter(Objects::nonNull)
								.findFirst().orElse(ProgramCalendar.weekStart(start, phase.firstWeek())),
						request.weeks().stream().filter(week -> week.week() == phase.lastWeek())
								.map(ReplaceProgramWeeksRequest.WeekInput::endDate).filter(Objects::nonNull)
								.findFirst().orElse(ProgramCalendar.weekEnd(start, end, phase.lastWeek())),
						phase.expectedOutput(),
						savedIds.get(nameKey(phase.name()))))
				.toList();
		List<ReplaceProgramWeeksRequest.WeekInput> weeks = request.weeks();
		if (request.keepPast()) {
			phases = keepCompletedPhases(savedPhases, phases, today);
			weeks = keepPastWeeks(program, savedWeeks, weeks, today);
		}
		if (phases.size() > WeeklyProgramPhases.MAX_PHASES) {
			throw new InvalidRequestException("A program has at most " + WeeklyProgramPhases.MAX_PHASES + " phases.");
		}
		requireReadable(actorId, program, request.documentId());
		coordinatorProgramService.writePhases(programId, phases);
		ProgramWeekPlanResponse plan = programWeekPlanService.writeWeeks(program, actorId, weeks);
		if (request.specialDays() != null) {
			List<ReplaceProgramWeeksRequest.SpecialDayInput> events = request.keepPast()
					? programWeekPlanService.keepPastSpecialDays(programId, request.specialDays(), today)
					: request.specialDays();
			programWeekPlanService.writeSpecialDays(program, actorId, events);
			plan = programWeekPlanService.plan(actorId, programId);
		}
		coordinatorProgramService.attachSyllabus(program, actorId, request.documentId());
		return new WeeklyProgramSavedResponse(coordinatorProgramService.detail(actorId, programId), plan);
	}

	/**
	 * A saved phase whose name comes back keeps its id, so Süreç assignments linked to it stay linked when the plan
	 * is read again from a file.
	 */
	private static Map<String, UUID> idsByName(List<ProgramPhase> saved) {
		Map<String, UUID> ids = new HashMap<>();
		saved.forEach(phase -> ids.putIfAbsent(nameKey(phase.getName()), phase.getId()));
		return ids;
	}

	private static String nameKey(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
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
				.filter(document -> document.getKind() == DocumentKind.WEEKLY_PROGRAM)
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
						phase.getEndDate(), phase.getExpectedOutput(), phase.getId()));
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
					phase.expectedOutput(), phase.id()));
		}
		return phases;
	}

	/** Saved weeks before the current one stay; the new plan fills the current week and those after it. */
	static List<ReplaceProgramWeeksRequest.WeekInput> keepPastWeeks(Program program, List<ProgramWeek> saved,
			List<ReplaceProgramWeeksRequest.WeekInput> proposed, LocalDate today) {
		ProgramSchedule schedule = ProgramSchedule.of(program.getStartDate(), program.getEndDate(), saved);
		List<ReplaceProgramWeeksRequest.WeekInput> weeks = new ArrayList<>();
		Set<Integer> kept = new LinkedHashSet<>();
		for (ProgramWeek week : saved) {
			if (schedule.isPast(week.getWeekNumber(), today)) {
				kept.add(week.getWeekNumber());
				weeks.add(new ReplaceProgramWeeksRequest.WeekInput(week.getWeekNumber(), week.getTitle(),
						ProgramWeek.lines(week.getTopics()), ProgramWeek.lines(week.getActivities()),
						ProgramWeek.lines(week.getDeliverables()), week.isInferred(), week.getStartDate(),
						week.getEndDate(),
						week.getSessions().stream().map(session -> new ReplaceProgramWeeksRequest.SessionInput(
								session.getDate(), session.getStartTime(), session.getEndTime(), session.getPlace(),
								session.getTitle())).toList()));
			}
		}
		for (ReplaceProgramWeeksRequest.WeekInput week : proposed) {
			LocalDate end = week.endDate() != null ? week.endDate()
					: ProgramCalendar.weekEnd(program.getStartDate(), program.getEndDate(), week.week());
			boolean futureSession = week.sessions() != null
					&& week.sessions().stream().anyMatch(session -> !session.date().isBefore(today));
			if (!kept.contains(week.week()) && (!end.isBefore(today) || futureSession)) {
				weeks.add(week);
			}
		}
		return weeks;
	}

	static @Nullable Integer firstReplacedWeek(Program program, List<ProgramWeek> saved, LocalDate today) {
		boolean ownDates = saved.stream()
				.anyMatch(week -> week.getStartDate() != null || !week.getSessions().isEmpty());
		if (!ownDates) {
			return firstReplacedWeek(program.getStartDate(), program.getEndDate(), today);
		}
		ProgramSchedule schedule = ProgramSchedule.of(program.getStartDate(), program.getEndDate(), saved);
		boolean begun = saved.stream().anyMatch(week -> {
			ProgramSchedule.Dates dates = schedule.datesOf(week.getWeekNumber());
			return dates != null && !dates.start().isAfter(today);
		});
		if (!begun) {
			return null;
		}
		return saved.stream().filter(week -> !schedule.isPast(week.getWeekNumber(), today))
				.mapToInt(ProgramWeek::getWeekNumber)
				.min().orElse(saved.stream().mapToInt(ProgramWeek::getWeekNumber).max().orElse(0) + 1);
	}

	private WeeklyProgramProposalResponse withExisting(WeeklyProgramProposalResponse proposal, Program program,
			LocalDate today) {
		UUID programId = program.getId();
		List<ProgramWeek> saved = programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId);
		return new WeeklyProgramProposalResponse(proposal.totalWeeks(), proposal.weeks(), proposal.phases(),
				proposal.warnings(), proposal.notes(), proposal.readingWarnings(), proposal.pageCount(),
				new WeeklyProgramProposalResponse.Existing(
						programPhaseRepository.findByProgramIdOrderByPositionAsc(programId).size(),
						saved.size(), firstReplacedWeek(program, saved, today)),
				proposal.specialDays());
	}

	/**
	 * The first week a keep-past save replaces, which the read reports so the coordinator is told what the save
	 * does: the running week, or one past the last after the end, when every week is past. Null before the start.
	 */
	static @Nullable Integer firstReplacedWeek(LocalDate start, LocalDate end, LocalDate today) {
		if (today.isAfter(end)) {
			return ProgramCalendar.weeksBetween(start, end) + 1;
		}
		return ProgramCalendar.currentWeek(start, end, today);
	}

	/**
	 * The AI's answer on the program calendar. Week numbers win over printed dates: a printed date places a week
	 * that has no number, or one whose number is not a program week, and one that falls outside its numbered week
	 * is flagged. A week number that comes back more than once (a table with one row per session) is merged into
	 * one week. Weeks past the program or without a title are dropped and flagged; a titled row the calendar cannot
	 * place at all (no number, no date in the program) becomes a note instead of disappearing.
	 */
	static WeeklyProgramProposalResponse proposalOf(JsonNode result, LocalDate start, LocalDate end, int totalWeeks,
			List<WeeklyProgramProposalResponse.Warning> warnings, int pageCount) {
		TreeMap<Integer, ProgramWeekPlanResponse.Week> weeks = new TreeMap<>();
		SortedMap<Integer, @Nullable String> weekPhases = new TreeMap<>();
		Set<Integer> dropped = new LinkedHashSet<>();
		Set<Integer> datesDiffer = new LinkedHashSet<>();
		List<String> unplaced = new ArrayList<>();
		for (JsonNode node : result.path("weeks")) {
			LocalDate printed = ModelOutput.date(node.path("source_date"));
			Integer byDate = weekOfDate(printed, start, totalWeeks);
			LocalDate printedEnd = ModelOutput.date(node.path("source_end_date"));
			boolean numbered = isWeekNumber(node.path("week"));
			Integer number = numbered ? node.path("week").asInt() : null;
			if (number == null || ((number < 1 || number > 104) && byDate != null)) {
				numbered = false;
				number = byDate;
			}
			String title = ModelOutput.text(node.path("title"), MAX_WEEK_TITLE_LENGTH);
			if (number == null) {
				addUnplaced(unplaced, title, printed);
				continue;
			}
			ProgramWeekPlanResponse.Week earlier = weeks.get(number);
			if (number < 1 || number > 104 || (number > totalWeeks && printed == null)
					|| (title == null && earlier == null)) {
				dropped.add(number);
				continue;
			}
			LocalDate weekStart = printed == null ? ProgramCalendar.weekStart(start, number) : printed;
			LocalDate weekEnd = printed == null ? ProgramCalendar.weekEnd(start, end, number)
					: printedEnd != null && !printedEnd.isBefore(printed) ? printedEnd : printed.plusDays(6);
			if (numbered && printed != null
					&& (printed.isBefore(ProgramCalendar.weekStart(start, number))
							|| printed.isAfter(ProgramCalendar.weekStart(start, number).plusDays(6)))) {
				datesDiffer.add(number);
			}
			ProgramWeekPlanResponse.Week week = new ProgramWeekPlanResponse.Week(number, weekStart, weekEnd,
					printed != null,
					title == null ? "" : title, lines(node.path("topics")), lines(node.path("activities")),
					lines(node.path("deliverables")), !numbered, sessionsOf(node.path("sessions")));
			weeks.put(number, earlier == null ? week : merged(earlier, week));
			if (weekPhases.get(number) == null) {
				weekPhases.put(number, ModelOutput.text(node.path("phase"), MAX_PHASE_NAME_LENGTH));
			}
		}
		int planWeeks = Math.max(totalWeeks, weeks.isEmpty() ? 0 : weeks.lastKey());
		WeeklyProgramPhases.Result grouped = WeeklyProgramPhases.group(rangesOf(result, planWeeks), weekPhases);
		List<WeeklyProgramProposalResponse.Warning> all = new ArrayList<>(warnings);
		addWarning(all, WeeklyProgramWarning.DATES_DIFFER, datesDiffer, false);
		addWarning(all, WeeklyProgramWarning.WEEKS_DROPPED, dropped, false);
		addWarning(all, WeeklyProgramWarning.PHASES_REBUILT, Set.of(), grouped.rebuilt());
		addWarning(all, WeeklyProgramWarning.PHASES_MERGED, Set.of(), grouped.merged());
		addWarning(all, WeeklyProgramWarning.NO_PHASES, Set.of(), !weeks.isEmpty() && grouped.phases().isEmpty());
		List<WeeklyProgramProposalResponse.Phase> phases = grouped.phases().stream()
				.map(range -> new WeeklyProgramProposalResponse.Phase(range.name(), range.expectedOutput(),
						range.firstWeek(), range.lastWeek(), phaseStart(weeks.get(range.firstWeek()), start,
								range.firstWeek()),
						phaseEnd(weeks.get(range.lastWeek()), start, end, range.lastWeek())))
				.toList();
		// Unplaced rows first: the AI's own notes must not push file content past the cap.
		List<String> notes = new ArrayList<>(unplaced);
		lines(result.path("notes"), MAX_NOTES).stream().filter(note -> !notes.contains(note)).forEach(notes::add);
		List<ProgramWeekPlanResponse.SpecialDay> specialDays = specialDaysOf(result.path("special_days"));
		List<ProgramWeekPlanResponse.Week> planned = List.copyOf(weeks.values());
		return new WeeklyProgramProposalResponse(planWeeks, planned, phases, all,
				List.copyOf(notes.subList(0, Math.min(notes.size(), MAX_NOTES))),
				lines(result.path("warnings"), MAX_NOTES), pageCount, null, List.copyOf(specialDays));
	}

	/** A phase starts on the first day of its first week: the printed one when the file prints it. */
	private static LocalDate phaseStart(ProgramWeekPlanResponse.@Nullable Week first, LocalDate start, int week) {
		return first != null && first.startDate() != null ? first.startDate() : ProgramCalendar.weekStart(start, week);
	}

	/** A phase ends on the last day of its last week: the printed one when the file prints it. */
	private static LocalDate phaseEnd(ProgramWeekPlanResponse.@Nullable Week last, LocalDate start, LocalDate end,
			int week) {
		return last != null && last.endDate() != null ? last.endDate() : ProgramCalendar.weekEnd(start, end, week);
	}

	/** The program week a printed date falls in; null without a date or outside the program. */
	private static @Nullable Integer weekOfDate(@Nullable LocalDate printed, LocalDate start, int totalWeeks) {
		if (printed == null || printed.isBefore(start)) {
			return null;
		}
		int week = (int) (ChronoUnit.DAYS.between(start, printed) / 7) + 1;
		return week <= totalWeeks ? week : null;
	}

	/**
	 * A second entry for a week, as a table with one row per session gives: its lines follow the first entry's,
	 * and its own title, when it differs, becomes a topic line so the session is not lost.
	 */
	private static ProgramWeekPlanResponse.Week merged(ProgramWeekPlanResponse.Week first,
			ProgramWeekPlanResponse.Week next) {
		// The title of a row that carries the week's printed number wins over one placed only by its date (an event
		// such as a bootcamp in the same week); the other title stays as a topic line where its row's lines are.
		boolean nextNames = first.title().isEmpty()
				|| (first.inferred() && !next.inferred() && !next.title().isEmpty());
		String title = nextNames ? next.title() : first.title();
		List<String> firstTopics = withTitle(first, title);
		List<String> nextTopics = withTitle(next, title);
		ProgramWeekPlanResponse.Week dated = first.datesFromSource() ? first : next;
		return new ProgramWeekPlanResponse.Week(first.week(), dated.startDate(), dated.endDate(),
				first.datesFromSource() || next.datesFromSource(), title,
				joined(firstTopics, nextTopics), joined(first.activities(), next.activities()),
				joined(first.deliverables(), next.deliverables()), first.inferred() && next.inferred(),
				Stream.concat(first.sessions().stream(), next.sessions().stream()).distinct()
						.toList());
	}

	/** The entry's topics, led by its own title when that is not the week's title. */
	private static List<String> withTitle(ProgramWeekPlanResponse.Week entry, String weekTitle) {
		List<String> topics = new ArrayList<>();
		if (!entry.title().isEmpty() && !entry.title().equalsIgnoreCase(weekTitle)) {
			topics.add(cut(entry.title()));
		}
		topics.addAll(entry.topics());
		return topics;
	}

	private static List<String> joined(List<String> first, List<String> next) {
		Set<String> lines = new LinkedHashSet<>(first);
		lines.addAll(next);
		return lines.stream().limit(MAX_WEEK_LINES).toList();
	}

	private static void addUnplaced(List<String> unplaced, @Nullable String title, @Nullable LocalDate printed) {
		if (title == null) {
			return;
		}
		String note = cut(printed == null ? title : NOTE_DATE.format(printed) + " — " + title);
		if (!unplaced.contains(note)) {
			unplaced.add(note);
		}
	}

	private static String cut(String line) {
		return line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH).trim() : line;
	}

	/** The AI's phase ranges, cut to the program; a range wholly past the program is left out. */
	private static List<WeeklyProgramPhases.Range> rangesOf(JsonNode result, int totalWeeks) {
		List<WeeklyProgramPhases.Range> ranges = new ArrayList<>();
		for (JsonNode node : result.path("phases")) {
			String name = ModelOutput.text(node.path("name"), MAX_PHASE_NAME_LENGTH);
			if (name == null || !isWeekNumber(node.path("first_week")) || !isWeekNumber(node.path("last_week"))) {
				continue;
			}
			int first = node.path("first_week").asInt();
			int last = Math.min(node.path("last_week").asInt(), totalWeeks);
			if (first > totalWeeks) {
				continue;
			}
			ranges.add(new WeeklyProgramPhases.Range(name, ModelOutput.text(node.path("expected_output"),
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

	private static LocalDate today() {
		return LocalDate.now(MeetingSlotTimes.APP_ZONE);
	}

	private static List<String> lines(JsonNode array) {
		return lines(array, MAX_WEEK_LINES);
	}

	/** Table cells such as "–" mean "nothing this week", so they are dropped. */
	private static List<String> lines(JsonNode array, int max) {
		return ModelOutput.lines(array, max, MAX_LINE_LENGTH, true);
	}

	private static @Nullable LocalTime timeOf(JsonNode node) {
		try {
			return node.isString() ? LocalTime.parse(node.stringValue()) : null;
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private static List<ProgramWeekPlanResponse.Session> sessionsOf(JsonNode nodes) {
		List<ProgramWeekPlanResponse.Session> sessions = new ArrayList<>();
		for (JsonNode node : nodes) {
			LocalDate date = ModelOutput.date(node.path("date"));
			if (date != null && sessions.size() < ReplaceProgramWeeksRequest.MAX_SESSIONS) {
				sessions.add(new ProgramWeekPlanResponse.Session(date, timeOf(node.path("start_time")),
						timeOf(node.path("end_time")), ModelOutput.text(node.path("place"), 160),
						ModelOutput.text(node.path("title"), 160)));
			}
		}
		return sessions;
	}

	private static List<ProgramWeekPlanResponse.SpecialDay> specialDaysOf(JsonNode nodes) {
		List<ProgramWeekPlanResponse.SpecialDay> days = new ArrayList<>();
		for (JsonNode node : nodes) {
			LocalDate date = ModelOutput.date(node.path("date"));
			String title = ModelOutput.text(node.path("title"), 160);
			if (date != null && title != null && days.size() < ReplaceProgramWeeksRequest.MAX_SPECIAL_DAYS) {
				days.add(new ProgramWeekPlanResponse.SpecialDay(date, timeOf(node.path("start_time")),
						timeOf(node.path("end_time")), ModelOutput.text(node.path("place"), 160), title,
						lines(node.path("details"))));
			}
		}
		return days;
	}

	private static JsonNode schema() {
		JsonNode session = object(property("date", string()), property("start_time", nullableString()),
				property("end_time", nullableString()), property("place", nullableString()),
				property("title", nullableString()));
		JsonNode specialDay = object(property("date", string()), property("start_time", nullableString()),
				property("end_time", nullableString()), property("place", nullableString()),
				property("title", string()), property("details", stringArray()));
		JsonNode week = object(property("week", nullableInteger()), property("source_date", nullableString()),
				property("source_end_date", nullableString()), property("sessions", arrayOf(session)),
				property("title", string()), property("topics", stringArray()), property("activities", stringArray()),
				property("deliverables", stringArray()), property("phase", nullableString()));
		JsonNode phase = object(property("name", string()), property("expected_output", nullableString()),
				property("first_week", integer()), property("last_week", integer()));
		return object(property("weeks", arrayOf(week)), property("phases", arrayOf(phase)),
				property("notes", stringArray()), property("warnings", stringArray()),
				property("special_days", arrayOf(specialDay)));
	}
}
