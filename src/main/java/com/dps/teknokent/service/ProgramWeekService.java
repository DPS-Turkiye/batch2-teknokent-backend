package com.dps.teknokent.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.dto.responses.ProgramWeekResponse;
import com.dps.teknokent.dto.responses.ProgramWeeksSoFarResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramSpecialDay;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramSpecialDayRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.ProgramSchedule;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * The current week of a participant's program and the syllabus phases in it, and the weeks of its plan reached so far.
 * Open to the program's approved mentees (a startup's members or an individual student) and its accepted mentors;
 * nobody else. A week that has not begun is never sent to them.
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
	private final ProgramSpecialDayRepository specialDays;

	/** {@code programId} picks one of a mentor's programs; a mentee's own program is used when it is left out. */
	@Transactional(readOnly = true)
	public ProgramWeekResponse thisWeek(UUID actorId, UUID programId) {
		return weekOf(actorId, programId, LocalDate.now(MeetingSlotTimes.APP_ZONE));
	}

	ProgramWeekResponse weekOf(UUID actorId, @Nullable UUID requestedProgramId, LocalDate today) {
		Program program = requireParticipantProgram(actorId, requestedProgramId);
		UUID programId = program.getId();
		ProgramWeekResponse response = build(program,
				programPhaseRepository.findByProgramIdOrderByPositionAsc(programId),
				programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId), today);
		List<ProgramWeekPlanResponse.SpecialDay> todayEvents = specialDays.findByProgramIdOrderByDateAsc(programId)
				.stream()
				.filter(day -> day.getDate().equals(today)).map(ProgramWeekPlanService::specialDayOf).toList();
		return new ProgramWeekResponse(response.programId(), response.programName(), response.programStart(),
				response.programEnd(),
				response.week(), response.totalWeeks(), response.weekStart(), response.weekEnd(),
				todayEvents.isEmpty() && response.notStarted(), todayEvents.isEmpty() && response.ended(),
				response.hasSyllabus() || !todayEvents.isEmpty(), response.plan(), response.phases(), todayEvents);
	}

	/**
	 * "Geçmiş haftalar" on Süreç: the saved weeks the caller's program has reached, up to the running one. Open to the
	 * same people as {@link #thisWeek}.
	 */
	@Transactional(readOnly = true)
	public ProgramWeeksSoFarResponse weeksSoFar(UUID actorId, @Nullable UUID programId) {
		return weeksSoFar(actorId, programId, LocalDate.now(MeetingSlotTimes.APP_ZONE));
	}

	ProgramWeeksSoFarResponse weeksSoFar(UUID actorId, @Nullable UUID requestedProgramId, LocalDate today) {
		Program program = requireParticipantProgram(actorId, requestedProgramId);
		return reached(program, programWeekRepository.findByProgramIdOrderByWeekNumberAsc(program.getId()),
				specialDays.findByProgramIdOrderByDateAsc(program.getId()), today);
	}

	/**
	 * The saved weeks that have begun by {@code today}, each with its sessions and special days. A week is in once its
	 * first day has come (or it is the running week, which a session dated today can open early). Nothing dated after
	 * the running week's last day goes out, because a session or special day of an earlier week can still lie ahead.
	 */
	static ProgramWeeksSoFarResponse reached(Program program, List<ProgramWeek> plan, List<ProgramSpecialDay> days,
			LocalDate today) {
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		ProgramSchedule schedule = ProgramSchedule.of(start, end, plan);
		Integer running = schedule.weekOn(today);
		ProgramSchedule.Dates runningDates = running == null ? null : schedule.datesOf(running);
		LocalDate horizon = runningDates == null || runningDates.end().isBefore(today) ? today : runningDates.end();
		List<ProgramWeeksSoFarResponse.Week> weeks = new ArrayList<>();
		for (ProgramWeek item : plan) {
			int number = item.getWeekNumber();
			boolean current = running != null && running == number;
			ProgramSchedule.Dates dates = schedule.datesOf(number);
			if (!current && (dates == null || dates.start().isAfter(today))) {
				continue;
			}
			ProgramWeekPlanResponse.Week week = ProgramWeekPlanService.weekOf(item, start, end);
			List<ProgramWeekPlanResponse.Session> sessions = week.sessions().stream()
					.filter(session -> !session.date().isAfter(horizon))
					.toList();
			List<ProgramWeekPlanResponse.SpecialDay> inWeek = dates == null ? List.of()
					: specialDaysIn(days, dates, horizon);
			weeks.add(new ProgramWeeksSoFarResponse.Week(new ProgramWeekPlanResponse.Week(week.week(),
					week.startDate(), week.endDate(), week.datesFromSource(), week.title(), week.topics(),
					week.activities(), week.deliverables(), week.inferred(), sessions), current, inWeek));
		}
		return new ProgramWeeksSoFarResponse(program.getId(), program.getName(), running, weeks);
	}

	private static List<ProgramWeekPlanResponse.SpecialDay> specialDaysIn(List<ProgramSpecialDay> days,
			ProgramSchedule.Dates week, LocalDate horizon) {
		return days.stream()
				.filter(day -> !day.getDate().isBefore(week.start()) && !day.getDate().isAfter(week.end())
						&& !day.getDate().isAfter(horizon))
				.map(ProgramWeekPlanService::specialDayOf)
				.toList();
	}

	/**
	 * The program {@code requestedProgramId} names (a mentee's own when left out), once the caller is an approved
	 * participant of it: one of its mentees, or a mentor it accepted. Anyone else gets 403; a mentee in no program 404.
	 */
	private Program requireParticipantProgram(UUID actorId, @Nullable UUID requestedProgramId) {
		AppUser caller = domainLookupService.requireUser(actorId);
		Optional<UUID> ownProgram = caller.has(UserRole.MENTEE)
				? menteeProgramService.programOf(caller)
				: Optional.empty();
		UUID programId = requestedProgramId != null
				? requestedProgramId
				: ownProgram.orElseThrow(() -> new ResourceNotFoundException("The caller is in no program"));
		boolean participant = ownProgram.filter(programId::equals).isPresent() || mentorsIn(caller, programId);
		if (caller.getStatus() != UserAccountStatus.APPROVED || !participant) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					Texts.forRequest("error.programWeekParticipantsOnly"));
		}
		return programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
	}

	static ProgramWeekResponse build(Program program, List<ProgramPhase> phases, List<ProgramWeek> plan,
			LocalDate today) {
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		ProgramSchedule schedule = ProgramSchedule.of(start, end, plan);
		Integer week = schedule.weekOn(today);
		boolean notStarted = week == null && start != null && today.isBefore(start);
		boolean ended = week == null && end != null && today.isAfter(end);
		ProgramSchedule.Dates dates = week == null ? null : schedule.datesOf(week);
		LocalDate from = dates == null ? today : dates.start();
		LocalDate to = dates == null ? today : dates.end();
		List<ProgramWeekResponse.Phase> inWeek = notStarted || ended
				? List.of()
				: ProgramCalendar.phasesBetween(phases, from, to).stream()
						.map(phase -> new ProgramWeekResponse.Phase(phase.getId(), phase.getName(), phase.getPosition(),
								phase.getStartDate(), phase.getEndDate(), phase.getExpectedOutput(),
								covers(phase, today)))
						.toList();
		ProgramWeekPlanResponse.Week planned = week == null ? null
				: plan.stream()
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
		return caller.has(UserRole.MENTOR)
				&& programMentorRepository.findByProgramIdAndMentorId(programId, caller.getId())
						.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
						.isPresent();
	}
}
