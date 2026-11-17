package com.dps.teknokent.service;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AttendanceState;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AttendanceStatusResponse;
import com.dps.teknokent.dto.responses.ProgramAttendanceReportResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramAttendance;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramAttendanceRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.ProgramSchedule;

import lombok.RequiredArgsConstructor;

/**
 * Weekly attendance: a participant checks in for the program week running today, and the program's coordinators
 * read who did. Weeks are counted from the program's start date ({@link ProgramCalendar}); a participant can check in
 * on any day of the running week, never for a week that has not begun or has already ended.
 */
@Service
@RequiredArgsConstructor
public class ProgramAttendanceService {

	private static final Collator TURKISH = Collator.getInstance(Locale.forLanguageTag("tr"));

	private final DomainLookupService domainLookupService;
	private final MenteeProgramService menteeProgramService;
	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final OrganizationRepository organizationRepository;
	private final AppUserRepository appUserRepository;
	private final ProgramAttendanceRepository attendanceRepository;
	private final ProgramWeekRepository programWeeks;

	/** Where a program stands on {@code today}: the running week and its days, or why no week is open. */
	record Window(AttendanceState state, @Nullable Integer week, @Nullable Integer totalWeeks,
			@Nullable LocalDate weekStart, @Nullable LocalDate weekEnd) {
	}

	static Window window(Program program, LocalDate today) {
		return window(program, today, List.of());
	}

	static Window window(Program program, LocalDate today, List<ProgramWeek> weeks) {
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		if (program.getStatus() != ProgramStatus.ACTIVE) {
			return new Window(AttendanceState.UNSCHEDULED, null, null, null, null);
		}
		Integer total = ProgramCalendar.totalWeeks(start, end);
		ProgramSchedule schedule = ProgramSchedule.of(start, end, weeks);
		Integer storedWeek = schedule.weekOn(today);
		if (storedWeek != null) {
			ProgramSchedule.Dates dates = schedule.datesOf(storedWeek);
			if (dates != null) {
				return new Window(AttendanceState.OPEN, storedWeek, total, dates.start(), dates.end());
			}
		}
		if (start == null) {
			return new Window(AttendanceState.UNSCHEDULED, null, total, null, null);
		}
		if (today.isBefore(start)) {
			return new Window(AttendanceState.NOT_STARTED, null, total, null, null);
		}
		if (end != null && today.isAfter(end)) {
			return new Window(AttendanceState.ENDED, null, total, null, null);
		}
		int week = ProgramCalendar.weekOn(start, end, today);
		return new Window(AttendanceState.OPEN, week, total, weekStart(start, week), weekEnd(start, end, week));
	}

	static LocalDate weekStart(LocalDate programStart, int week) {
		return programStart.plusDays(7L * (week - 1));
	}

	/** The week's last day; the program's last day when that comes first. */
	static LocalDate weekEnd(LocalDate programStart, LocalDate programEnd, int week) {
		LocalDate end = weekStart(programStart, week).plusDays(6);
		return programEnd != null && programEnd.isBefore(end) ? programEnd : end;
	}

	@Transactional(readOnly = true)
	public AttendanceStatusResponse status(UUID actorId) {
		AppUser actor = requireApproved(actorId);
		Optional<Program> program = programOf(actor);
		if (program.isEmpty()) {
			return new AttendanceStatusResponse(null, null, AttendanceState.NO_PROGRAM, null, null, null, null, null);
		}
		return statusOf(actor, program.get(), window(program.get(), today(),
				programWeeks.findByProgramIdOrderByWeekNumberAsc(program.get().getId())));
	}

	@Transactional
	public AttendanceStatusResponse checkIn(UUID actorId, int week) {
		AppUser actor = requireApproved(actorId);
		Program program = programOf(actor)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
						"Only a program's participants check in to it."));
		Window window = window(program, today(), programWeeks.findByProgramIdOrderByWeekNumberAsc(program.getId()));
		Integer running = window.week();
		if (window.state() != AttendanceState.OPEN || running == null) {
			throw new ConflictException("No program week is open for check-in.");
		}
		if (week != running) {
			throw new InvalidRequestException("Only the running week (" + running + ") can be checked in.");
		}
		if (attendanceRepository.findByProgramIdAndUserIdAndWeekNumber(program.getId(), actor.getId(), week)
				.isPresent()) {
			throw new ConflictException("Already checked in for this week.");
		}
		ProgramAttendance attendance = new ProgramAttendance();
		attendance.setId(UUID.randomUUID());
		attendance.setProgramId(program.getId());
		attendance.setUserId(actor.getId());
		attendance.setWeekNumber(week);
		attendance.setCheckedInAt(Instant.now());
		try {
			attendanceRepository.saveAndFlush(attendance);
		} catch (DataIntegrityViolationException raced) {
			throw new ConflictException("Already checked in for this week.");
		}
		return statusOf(actor, program, window);
	}

	/**
	 * Who checked in, per week, for the weeks that overlap {@code from}–{@code to} and have begun: the program's
	 * start to today when left out. Only the program's coordinators and admins read it; anyone else gets 403.
	 */
	@Transactional(readOnly = true)
	public ProgramAttendanceReportResponse report(UUID actorId, UUID programId, LocalDate from, LocalDate to) {
		Program program = requireReportReader(actorId, programId);
		if (from != null && to != null && to.isBefore(from)) {
			throw new InvalidRequestException("The report cannot end before it starts.");
		}
		LocalDate today = today();
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		List<ProgramWeek> savedWeeks = programWeeks.findByProgramIdOrderByWeekNumberAsc(programId);
		ProgramSchedule schedule = ProgramSchedule.of(start, end, savedWeeks);
		int total = Math.max(
				ProgramCalendar.totalWeeks(start, end) == null ? 0 : ProgramCalendar.weeksBetween(start, end),
				savedWeeks.stream().mapToInt(ProgramWeek::getWeekNumber).max().orElse(0));
		int lastBegun = schedule.lastBegunWeek(total, today);
		if (lastBegun == 0) {
			return new ProgramAttendanceReportResponse(program.getId(), from, to, List.of(), List.of());
		}
		LocalDate printedFirst = IntStream.rangeClosed(1, lastBegun).mapToObj(schedule::datesOf)
				.filter(Objects::nonNull).map(ProgramSchedule.Dates::start).min(LocalDate::compareTo)
				.orElse(start);
		LocalDate first = from != null ? from : printedFirst;
		LocalDate printedLast = savedWeeks.stream().map(ProgramWeek::getEndDate).filter(Objects::nonNull)
				.max(LocalDate::compareTo).orElse(end);
		LocalDate effectiveEnd = end == null || (printedLast != null && printedLast.isAfter(end)) ? printedLast : end;
		LocalDate last = to != null ? to
				: (effectiveEnd != null && effectiveEnd.isBefore(today) ? effectiveEnd : today);

		List<Integer> weekNumbers = new ArrayList<>();
		for (int week = 1; week <= lastBegun; week++) {
			ProgramSchedule.Dates dates = schedule.datesOf(week);
			if (dates != null && !dates.start().isAfter(last) && !dates.end().isBefore(first)) {
				weekNumbers.add(week);
			}
		}

		Set<String> checkedIn = new HashSet<>();
		if (!weekNumbers.isEmpty()) {
			attendanceRepository.findByProgramIdAndWeekNumberBetween(
					program.getId(), weekNumbers.getFirst(), weekNumbers.getLast())
					.forEach(row -> checkedIn.add(row.getUserId() + ":" + row.getWeekNumber()));
		}
		List<Person> people = participantsOf(program);

		List<ProgramAttendanceReportResponse.Week> weeks = weekNumbers.stream().map(week -> {
			int count = 0;
			int expected = 0;
			for (Person person : people) {
				boolean attended = checkedIn.contains(person.user().getId() + ":" + week);
				// Someone who has left the program counts only in the weeks they checked in.
				if (person.current() || attended) {
					expected++;
				}
				if (attended) {
					count++;
				}
			}
			Integer rate = expected == 0 ? null : Math.round(count * 100f / expected);
			return new ProgramAttendanceReportResponse.Week(
					week, Objects.requireNonNull(schedule.datesOf(week)).start(),
					Objects.requireNonNull(schedule.datesOf(week)).end(), count, expected, rate);
		}).toList();
		List<ProgramAttendanceReportResponse.Participant> rows = people.stream().map(person -> {
			List<Boolean> attended = weekNumbers.stream()
					.map(week -> checkedIn.contains(person.user().getId() + ":" + week))
					.toList();
			return new ProgramAttendanceReportResponse.Participant(
					person.user().getFullName(),
					person.user().getEmail(),
					person.current() ? teamNameOrNull(person.user()) : null,
					attended,
					(int) attended.stream().filter(Boolean::booleanValue).count());
		}).toList();
		return new ProgramAttendanceReportResponse(program.getId(), first, last, weeks, rows);
	}

	/** A participant row; {@code current} is false for someone who checked in here earlier but has since left. */
	private record Person(AppUser user, boolean current) {
	}

	/**
	 * The program's participants: approved mentees on a team whose program it is (the startup's chosen or latest
	 * program, or a student's personal team), plus anyone who already checked in to it, kept as a former
	 * participant so a later team change keeps the history. Mentees without a team are left out: they have no
	 * Süreç to check in from.
	 */
	private List<Person> participantsOf(Program program) {
		Map<UUID, Person> people = new LinkedHashMap<>();
		List<UUID> teamIds = programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId()).stream()
				.map(ProgramStartup::getOrganizationId)
				.toList();
		List<UUID> teamsHere = organizationRepository.findAllById(teamIds).stream()
				.filter(team -> menteeProgramService.programOf(team).filter(program.getId()::equals).isPresent())
				.map(Organization::getId)
				.toList();
		if (!teamsHere.isEmpty()) {
			appUserRepository.findByOrganizationIdInOrderByFullNameAsc(teamsHere).stream()
					.filter(ProgramAttendanceService::isActiveMentee)
					.forEach(person -> people.put(person.getId(), new Person(person, true)));
		}
		appUserRepository.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(
				program.getId(), UserRole.MENTEE, UserAccountStatus.APPROVED).stream()
				.filter(person -> person.getOrganization() != null && person.getOrganization().isPersonal())
				.forEach(person -> people.putIfAbsent(person.getId(), new Person(person, true)));
		Set<UUID> earlier = new HashSet<>();
		attendanceRepository.findByProgramId(program.getId()).forEach(row -> earlier.add(row.getUserId()));
		earlier.removeAll(people.keySet());
		appUserRepository.findAllById(earlier).forEach(person -> people.put(person.getId(), new Person(person, false)));
		return people.values().stream()
				.sorted(Comparator.comparingInt((Person person) -> person.current() ? 0 : 1)
						.thenComparing(person -> teamName(person.user()), TURKISH)
						.thenComparing(person -> String.valueOf(person.user().getFullName()), TURKISH))
				.toList();
	}

	private static @Nullable String teamNameOrNull(AppUser person) {
		String name = teamName(person);
		return name.isEmpty() ? null : name;
	}

	private static String teamName(AppUser person) {
		Organization team = person.getOrganization();
		return team == null || team.isPersonal() || team.getName() == null ? "" : team.getName();
	}

	private static boolean isActiveMentee(AppUser person) {
		return person.has(UserRole.MENTEE) && person.getStatus() == UserAccountStatus.APPROVED;
	}

	private AttendanceStatusResponse statusOf(AppUser actor, Program program, Window window) {
		Integer running = window.week();
		Instant checkedInAt = window.state() == AttendanceState.OPEN && running != null
				? attendanceRepository
						.findByProgramIdAndUserIdAndWeekNumber(program.getId(), actor.getId(), running)
						.map(ProgramAttendance::getCheckedInAt)
						.orElse(null)
				: null;
		AttendanceState state = checkedInAt != null ? AttendanceState.CHECKED_IN : window.state();
		return new AttendanceStatusResponse(program.getId(), program.getName(), state, window.week(),
				window.totalWeeks(), window.weekStart(), window.weekEnd(), checkedInAt);
	}

	/** A participant is an approved mentee; their program is the one {@link MenteeProgramService} picks. */
	private AppUser requireApproved(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getStatus() != UserAccountStatus.APPROVED) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not approved.");
		}
		return actor;
	}

	private Optional<Program> programOf(AppUser actor) {
		if (!actor.has(UserRole.MENTEE)) {
			return Optional.empty();
		}
		return menteeProgramService.programOf(actor).flatMap(programRepository::findById);
	}

	private Program requireReportReader(UUID actorId, UUID programId) {
		try {
			return coordinatorProgramService.requireManagedProgram(actorId, programId);
		} catch (InvalidRequestException refused) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Only the program's coordinators and admins can read its attendance.");
		}
	}

	private static LocalDate today() {
		return LocalDate.now(MeetingSlotTimes.APP_ZONE);
	}
}
