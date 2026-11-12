package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.ProgramCalendar;

import lombok.RequiredArgsConstructor;

/**
 * Program Ayarları › Müfredat & fazlar › Haftalık plan: the weeks the coordinator saved after reviewing the AI's
 * proposal. Coordinators and admins read it; only the program's coordinators change it, as with the phases.
 */
@Service
@RequiredArgsConstructor
public class ProgramWeekPlanService {

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramWeekRepository programWeekRepository;

	@Transactional(readOnly = true)
	public ProgramWeekPlanResponse plan(UUID actorId, UUID programId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		return planOf(program, programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId));
	}

	@Transactional
	public ProgramWeekPlanResponse replace(UUID actorId, UUID programId, ReplaceProgramWeeksRequest request) {
		Program program = coordinatorProgramService.requireSettingsEditor(actorId, programId);
		return writeWeeks(program, actorId, request.weeks());
	}

	/** Replaces the program's whole weekly plan with {@code inputs}; the caller has checked who may. */
	ProgramWeekPlanResponse writeWeeks(
			Program program, UUID actorId, List<ReplaceProgramWeeksRequest.WeekInput> inputs) {
		UUID programId = program.getId();
		Integer total = ProgramCalendar.totalWeeks(program.getStartDate(), program.getEndDate());
		Set<Integer> seen = new HashSet<>();
		for (ReplaceProgramWeeksRequest.WeekInput input : inputs) {
			if (!seen.add(input.week())) {
				throw new InvalidRequestException("Each week can appear only once in the plan.");
			}
			if (total != null && input.week() > total) {
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
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		return new ProgramWeekPlanResponse(ProgramCalendar.totalWeeks(start, end),
				weeks.stream().map(week -> weekOf(week, start, end)).toList());
	}

	static ProgramWeekPlanResponse.Week weekOf(ProgramWeek week, LocalDate start, LocalDate end) {
		int number = week.getWeekNumber();
		return new ProgramWeekPlanResponse.Week(number,
				start == null ? null : ProgramCalendar.weekStart(start, number),
				start == null ? null : ProgramCalendar.weekEnd(start, end, number),
				week.getTitle(), ProgramWeek.lines(week.getTopics()), ProgramWeek.lines(week.getActivities()),
				ProgramWeek.lines(week.getDeliverables()), week.isInferred());
	}
}
