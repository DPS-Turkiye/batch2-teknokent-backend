package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramSpecialDay;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramSpecialDayRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.ProgramSchedule;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Program Ayarları › Müfredat & fazlar › Haftalık plan: the weeks the coordinator saved after reviewing the AI's
 * proposal. Coordinators and admins read it; only the program's coordinators change it, as with the phases.
 */
@Service
@RequiredArgsConstructor
public class ProgramWeekPlanService {

	/** A printed "week" longer than a month is a misread range, not a week. */
	private static final int MAX_WEEK_DAYS = 31;
	private static final int MAX_PROGRAM_WEEKS = 104;

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramWeekRepository programWeekRepository;
	private final ProgramSpecialDayRepository specialDays;

	@Transactional(readOnly = true)
	public ProgramWeekPlanResponse plan(UUID actorId, UUID programId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		return readPlan(program);
	}

	@Transactional
	public ProgramWeekPlanResponse replace(UUID actorId, UUID programId, ReplaceProgramWeeksRequest request) {
		Program program = coordinatorProgramService.requireSettingsEditor(actorId, programId);
		writeWeeks(program, actorId, request.weeks());
		writeSpecialDays(program, actorId, request.specialDays());
		return planOf(program, programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId),
				specialDays.findByProgramIdOrderByDateAsc(programId));
	}

	@Transactional
	public ProgramWeekPlanResponse alignDates(UUID actorId, UUID programId) {
		Program program = coordinatorProgramService.requireSettingsEditor(actorId, programId);
		ProgramWeekPlanResponse plan = readPlan(program);
		ProgramWeekPlanResponse.DateCheck check = plan.dateCheck();
		if (check != null) {
			int lastWeek = plan.weeks().stream().filter(week -> !week.datesFromSource())
					.mapToInt(ProgramWeekPlanResponse.Week::week).max().orElse(0);
			// A week without its own dates must stay inside the program, as a save requires.
			int weeks = ProgramCalendar.weeksBetween(check.syllabusStart(), check.syllabusEnd());
			if (weeks < lastWeek) {
				throw new InvalidRequestException("The syllabus dates leave fewer weeks than its plan has.");
			}
			// A misread year in one date would otherwise stretch the program by months; a plan holds at most 104 weeks.
			if (weeks > MAX_PROGRAM_WEEKS) {
				throw new InvalidRequestException(Texts.forRequest("error.programPlanTooLong"));
			}
			program.setStartDate(check.syllabusStart());
			program.setEndDate(check.syllabusEnd());
		}
		return readPlan(program);
	}

	private static void requireTimeOrder(@Nullable LocalTime start, @Nullable LocalTime end) {
		if (start != null && end != null && !end.isAfter(start)) {
			throw new InvalidRequestException(Texts.forRequest("error.programPlanTimeOrder"));
		}
	}

	private ProgramWeekPlanResponse readPlan(Program program) {
		return planOf(program, programWeekRepository.findByProgramIdOrderByWeekNumberAsc(program.getId()),
				specialDays.findByProgramIdOrderByDateAsc(program.getId()));
	}

	/**
	 * Saved past special days stay. A past one from the file is used only where the saved plan keeps nothing: not on
	 * a saved past day's date and not inside a kept week ({@code keptWeeks}), so a first save keeps the file's past.
	 */
	List<ReplaceProgramWeeksRequest.SpecialDayInput> keepPastSpecialDays(UUID programId,
			List<ReplaceProgramWeeksRequest.SpecialDayInput> proposed, List<ProgramSchedule.Dates> keptWeeks,
			LocalDate today) {
		List<ReplaceProgramWeeksRequest.SpecialDayInput> kept = new ArrayList<>();
		specialDays.findByProgramIdOrderByDateAsc(programId).stream().filter(day -> day.getDate().isBefore(today))
				.map(day -> new ReplaceProgramWeeksRequest.SpecialDayInput(day.getDate(), day.getStartTime(),
						day.getEndTime(), day.getPlace(), day.getTitle(), ProgramWeek.lines(day.getDetails())))
				.forEach(kept::add);
		Set<LocalDate> keptDates = kept.stream().map(ReplaceProgramWeeksRequest.SpecialDayInput::date)
				.collect(Collectors.toSet());
		proposed.stream()
				.filter(day -> !day.date().isBefore(today) || (!keptDates.contains(day.date())
						&& keptWeeks.stream().noneMatch(week -> week.contains(day.date()))))
				.forEach(kept::add);
		return kept;
	}

	void writeSpecialDays(Program program, UUID actorId, List<ReplaceProgramWeeksRequest.SpecialDayInput> inputs) {
		if (inputs == null) {
			return;
		}
		// A keep-past save adds the past days to the new ones, past the request's own limit.
		if (inputs.size() > ReplaceProgramWeeksRequest.MAX_SPECIAL_DAYS) {
			throw new InvalidRequestException(
					"A plan has at most " + ReplaceProgramWeeksRequest.MAX_SPECIAL_DAYS + " special days.");
		}
		for (ReplaceProgramWeeksRequest.SpecialDayInput input : inputs) {
			requireTimeOrder(input.startTime(), input.endTime());
		}
		specialDays.deleteByProgramId(program.getId());
		specialDays.flush();
		specialDays.saveAll(inputs.stream().map(input -> {
			ProgramSpecialDay day = new ProgramSpecialDay();
			day.setId(UUID.randomUUID());
			day.setProgramId(program.getId());
			day.setDate(input.date());
			day.setStartTime(input.startTime());
			day.setEndTime(input.endTime());
			day.setPlace(input.place());
			day.setTitle(input.title().trim());
			day.setDetails(ProgramWeek.joined(input.details()));
			day.setUpdatedAt(Instant.now());
			day.setUpdatedBy(actorId);
			return day;
		}).toList());
	}

	/** Replaces the program's whole weekly plan with {@code inputs}; the caller has checked who may. */
	ProgramWeekPlanResponse writeWeeks(
			Program program, UUID actorId, List<ReplaceProgramWeeksRequest.WeekInput> inputs) {
		UUID programId = program.getId();
		Integer total = ProgramCalendar.totalWeeks(program.getStartDate(), program.getEndDate());
		Set<Integer> seen = new HashSet<>();
		for (ReplaceProgramWeeksRequest.WeekInput input : inputs) {
			if ((input.startDate() == null) != (input.endDate() == null)
					|| (input.startDate() != null && input.endDate().isBefore(input.startDate()))) {
				throw new InvalidRequestException(Texts.forRequest("error.programPlanDateOrder"));
			}
			if (input.startDate() != null
					&& ChronoUnit.DAYS.between(input.startDate(), input.endDate()) >= MAX_WEEK_DAYS) {
				throw new InvalidRequestException(Texts.forRequest("error.programPlanWeekTooLong"));
			}
			if (input.sessions() != null) {
				input.sessions().forEach(session -> requireTimeOrder(session.startTime(), session.endTime()));
			}
			if (!seen.add(input.week())) {
				throw new InvalidRequestException("Each week can appear only once in the plan.");
			}
			if (total != null && input.week() > total && input.startDate() == null) {
				throw new InvalidRequestException("The program has only " + total + " weeks.");
			}
		}
		programWeekRepository.deleteByProgramId(programId);
		// Inserts run before deletes at flush; without this a kept week number would hit uq_program_week.
		programWeekRepository.flush();
		Instant now = Instant.now();
		List<ProgramWeek> weeks = inputs.stream()
				.sorted((left, right) -> Integer.compare(left.week(), right.week()))
				.map(input -> {
					ProgramWeek week = new ProgramWeek();
					week.setId(UUID.randomUUID());
					week.setProgramId(programId);
					week.setWeekNumber(input.week());
					week.setTitle(input.title().trim());
					week.setStartDate(input.startDate());
					week.setEndDate(input.endDate());
					if (input.sessions() != null) {
						week.getSessions().addAll(input.sessions().stream().map(session -> new ProgramWeekSession(
								session.date(), session.startTime(), session.endTime(), session.place(),
								session.title())).toList());
					}
					week.setTopics(ProgramWeek.joined(input.topics()));
					week.setActivities(ProgramWeek.joined(input.activities()));
					week.setDeliverables(ProgramWeek.joined(input.deliverables()));
					week.setInferred(input.inferred());
					week.setUpdatedAt(now);
					week.setUpdatedBy(actorId);
					return week;
				})
				.toList();
		return planOf(program, programWeekRepository.saveAll(weeks));
	}

	static ProgramWeekPlanResponse planOf(Program program, List<ProgramWeek> weeks) {
		return planOf(program, weeks, List.of());
	}

	static ProgramWeekPlanResponse planOf(Program program, List<ProgramWeek> weeks, List<ProgramSpecialDay> days) {
		List<LocalDate> printed = new ArrayList<>();
		for (ProgramWeek week : weeks) {
			if (week.getStartDate() != null && week.getEndDate() != null) {
				printed.add(week.getStartDate());
				printed.add(week.getEndDate());
			}
			week.getSessions().forEach(session -> printed.add(session.getDate()));
		}
		days.forEach(day -> printed.add(day.getDate()));
		ProgramWeekPlanResponse.DateCheck check = null;
		if (!printed.isEmpty()) {
			printed.sort(LocalDate::compareTo);
			LocalDate first = printed.getFirst();
			LocalDate last = printed.getLast();
			if (!first.equals(program.getStartDate()) || !last.equals(program.getEndDate())) {
				check = new ProgramWeekPlanResponse.DateCheck(first, last);
			}
		}
		return new ProgramWeekPlanResponse(ProgramCalendar.totalWeeks(program.getStartDate(), program.getEndDate()),
				weeks.stream().map(week -> weekOf(week, program.getStartDate(), program.getEndDate())).toList(),
				days.stream().map(ProgramWeekPlanService::specialDayOf).toList(), check);
	}

	static ProgramWeekPlanResponse.SpecialDay specialDayOf(ProgramSpecialDay day) {
		return new ProgramWeekPlanResponse.SpecialDay(day.getDate(), day.getStartTime(), day.getEndTime(),
				day.getPlace(), day.getTitle(), ProgramWeek.lines(day.getDetails()));
	}

	static ProgramWeekPlanResponse.Week weekOf(ProgramWeek week, LocalDate start, LocalDate end) {
		int number = week.getWeekNumber();
		ProgramSchedule schedule = ProgramSchedule.of(start, end, List.of(week));
		ProgramSchedule.Dates dates = schedule.datesOf(number);
		return new ProgramWeekPlanResponse.Week(number,
				dates == null ? null : dates.start(), dates == null ? null : dates.end(), schedule.printed(number),
				week.getTitle(), ProgramWeek.lines(week.getTopics()), ProgramWeek.lines(week.getActivities()),
				ProgramWeek.lines(week.getDeliverables()), week.isInferred(),
				week.getSessions().stream().map(session -> new ProgramWeekPlanResponse.Session(session.getDate(),
						session.getStartTime(), session.getEndTime(), session.getPlace(), session.getTitle()))
						.toList());
	}
}
