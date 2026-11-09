package com.dps.teknokent.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.dto.responses.ProgramWeekResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * The current week of a participant's program and the syllabus phases in it. Open to the program's approved mentees
 * (a startup's members or an individual student) and its accepted mentors; nobody else.
 */
@Service
@RequiredArgsConstructor
public class ProgramWeekService {

	private final DomainLookupService domainLookupService;
	private final MenteeProgramService menteeProgramService;
	private final ProgramRepository programRepository;
	private final ProgramPhaseRepository programPhaseRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramWeekRepository programWeekRepository;

	/** {@code programId} picks one of a mentor's programs; a mentee's own program is used when it is left out. */
	@Transactional(readOnly = true)
	public ProgramWeekResponse thisWeek(UUID actorId, UUID programId) {
		return weekOf(actorId, programId, LocalDate.now(MeetingSlotTimes.APP_ZONE));
	}

	ProgramWeekResponse weekOf(UUID actorId, UUID requestedProgramId, LocalDate today) {
		AppUser caller = domainLookupService.requireUser(actorId);
		Optional<UUID> ownProgram = caller.has(UserRole.MENTEE)
				? menteeProgramService.programOf(caller)
				: Optional.empty();
		UUID programId = requestedProgramId != null
				? requestedProgramId
				: ownProgram.orElseThrow(() -> new ResourceNotFoundException("The caller is in no program"));
		boolean participant = ownProgram.filter(programId::equals).isPresent() || mentorsIn(caller, programId);
		if (caller.getStatus() != UserAccountStatus.APPROVED || !participant) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.programWeekParticipantsOnly"));
		}
		Program program = programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
		return build(program, programPhaseRepository.findByProgramIdOrderByPositionAsc(programId),
				programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId), today);
	}

	static ProgramWeekResponse build(Program program, List<ProgramPhase> phases, List<ProgramWeek> plan,
			LocalDate today) {
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		boolean notStarted = start != null && today.isBefore(start);
		boolean ended = end != null && today.isAfter(end);
		Integer week = notStarted || ended ? null : ProgramCalendar.currentWeek(start, end, today);
		LocalDate from = week == null ? today : ProgramCalendar.weekStart(start, week);
		LocalDate to = week == null ? today : ProgramCalendar.weekEnd(start, end, week);
		List<ProgramWeekResponse.Phase> inWeek = notStarted || ended
				? List.of()
				: ProgramCalendar.phasesBetween(phases, from, to).stream()
						.map(phase -> new ProgramWeekResponse.Phase(phase.getId(), phase.getName(), phase.getPosition(),
								phase.getStartDate(), phase.getEndDate(), phase.getExpectedOutput(),
								covers(phase, today)))
						.toList();
		ProgramWeekPlanResponse.Week planned = week == null ? null : plan.stream()
				.filter(item -> item.getWeekNumber() == week)
				.findFirst()
				.map(item -> ProgramWeekPlanService.weekOf(item, start, end))
				.orElse(null);
		return new ProgramWeekResponse(program.getId(), program.getName(), start, end, week,
				ProgramCalendar.totalWeeks(start, end), week == null ? null : from, week == null ? null : to,
				notStarted, ended, !phases.isEmpty() || !plan.isEmpty(), planned, inWeek);
	}

	private static boolean covers(ProgramPhase phase, LocalDate day) {
		return !ProgramCalendar.phasesBetween(List.of(phase), day, day).isEmpty();
	}

	private boolean mentorsIn(AppUser caller, UUID programId) {
		return caller.has(UserRole.MENTOR) && programMentorRepository.findByProgramIdAndMentorId(programId, caller.getId())
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
				.isPresent();
	}
}
