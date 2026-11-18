package com.dps.teknokent.service;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AttendanceMethod;
import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.domain.AttendanceState;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AttendanceStatusResponse;
import com.dps.teknokent.dto.responses.ProgramAttendanceReportResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramAttendanceRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.AttendanceSessions;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCalendar;
import com.dps.teknokent.util.ProgramSchedule;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Attendance as participants and coordinators read it: a participant's sessions in the running week, and the
 * coordinator's report per session. Check-ins are written by {@link ProgramSessionAttendanceService}. Weeks are the
 * plan's own where it prints dates, else counted from the program's start date ({@link ProgramCalendar}).
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
		Optional<Program> found = programOf(actor);
		if (found.isEmpty()) {
			return new AttendanceStatusResponse(null, null, AttendanceState.NO_PROGRAM, null, null, null, null,
					List.of());
		}
		Program program = found.get();
		if (!program.isAttendanceEnabled()) {
			return new AttendanceStatusResponse(program.getId(), program.getName(), AttendanceState.DISABLED, null,
					null, null, null, List.of());
		}
		List<ProgramWeek> plan = programWeeks.findByProgramIdOrderByWeekNumberAsc(program.getId());
		Window window = window(program, today(), plan);
		Integer week = window.week();
		if (window.state() != AttendanceState.OPEN || week == null) {
			return new AttendanceStatusResponse(program.getId(), program.getName(), window.state(), null,
					window.totalWeeks(), null, null, List.of());
		}
		Map<AttendanceSessionKind, Instant> checkedIn = new EnumMap<>(AttendanceSessionKind.class);
		attendanceRepository.findByProgramIdAndUserIdAndWeekNumber(program.getId(), actor.getId(), week)
				.forEach(row -> checkedIn.put(row.getSessionKind(), row.getCheckedInAt()));
		List<AttendanceStatusResponse.Session> sessions = AttendanceSessions
				.of(ProgramSchedule.of(program.getStartDate(), program.getEndDate(), plan), plannedWeek(plan, week),
						week)
				.stream()
				.map(session -> new AttendanceStatusResponse.Session(session.kind(), session.date(),
						session.startTime(), session.endTime(), session.title(), checkedIn.get(session.kind())))
				.toList();
		return new AttendanceStatusResponse(program.getId(), program.getName(), AttendanceState.OPEN, week,
				window.totalWeeks(), window.weekStart(), window.weekEnd(), sessions);
	}

	/**
	 * Who attended, per session, for the weeks that overlap {@code from}–{@code to} and have begun: the program's
	 * start to today when left out. A week's lesson and workshop are listed once their day has come, and any session
	 * someone attended is listed even when the current plan no longer has it. Only the program's coordinators and
	 * admins read it; anyone else gets 403.
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
		int lastBegun = schedule.lastBegunWeek(totalWeeks(program, savedWeeks), today);
		if (lastBegun == 0) {
			return new ProgramAttendanceReportResponse(program.getId(), from, to, program.isAttendanceEnabled(),
					List.of(), List.of());
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

		Map<String, AttendanceMethod> attended = new HashMap<>();
		Map<Integer, Set<AttendanceSessionKind>> kindsAttended = new HashMap<>();
		if (!weekNumbers.isEmpty()) {
			attendanceRepository.findByProgramIdAndWeekNumberBetween(
					program.getId(), weekNumbers.getFirst(), weekNumbers.getLast())
					.forEach(row -> {
						attended.put(key(row.getUserId(), row.getWeekNumber(), row.getSessionKind()), row.getMethod());
						kindsAttended.computeIfAbsent(row.getWeekNumber(),
								week -> EnumSet.noneOf(AttendanceSessionKind.class)).add(row.getSessionKind());
					});
		}
		List<SessionColumn> columns = new ArrayList<>();
		for (int week : weekNumbers) {
			columns.addAll(columnsOf(week, AttendanceSessions.of(schedule, plannedWeek(savedWeeks, week), week),
					kindsAttended.getOrDefault(week, Set.of()), today));
		}
		List<Person> people = participantsOf(program);

		List<ProgramAttendanceReportResponse.Session> sessions = columns.stream().map(column -> {
			int count = 0;
			int expected = 0;
			for (Person person : people) {
				boolean present = attended.containsKey(key(person.user().getId(), column.week(), column.kind()));
				// Someone who has left the program counts only in the sessions they attended.
				if (person.current() || present) {
					expected++;
				}
				if (present) {
					count++;
				}
			}
			Integer rate = expected == 0 ? null : Math.round(count * 100f / expected);
			AttendanceSessions.Session session = column.session();
			return new ProgramAttendanceReportResponse.Session(column.week(), column.kind(),
					session == null ? null : session.date(), session == null ? null : session.startTime(),
					session == null ? null : session.endTime(), session == null ? null : session.title(), count,
					expected, rate);
		}).toList();
		List<ProgramAttendanceReportResponse.Participant> rows = people.stream().map(person -> {
			List<ProgramAttendanceReportResponse.Mark> marks = columns.stream()
					.map(column -> attended.get(key(person.user().getId(), column.week(), column.kind())))
					.map(method -> new ProgramAttendanceReportResponse.Mark(method != null, method))
					.toList();
			return new ProgramAttendanceReportResponse.Participant(
					person.user().getId(),
					person.user().getFullName(),
					person.user().getEmail(),
					person.current() ? teamNameOrNull(person.user()) : null,
					person.current(),
					marks,
					(int) marks.stream().filter(ProgramAttendanceReportResponse.Mark::attended).count());
		}).toList();
		return new ProgramAttendanceReportResponse(program.getId(), first, last, program.isAttendanceEnabled(),
				sessions, rows);
	}

	/** A report column: a session of a week, with its day and time from the plan (null when the plan lacks it). */
	private record SessionColumn(int week, AttendanceSessionKind kind, AttendanceSessions.@Nullable Session session) {
	}

	/**
	 * Week {@code week}'s columns: its lesson and workshop once their day has come, a session someone attended that
	 * the plan no longer has, and the weekly self-check-ins from before per-session attendance.
	 */
	private static List<SessionColumn> columnsOf(int week, List<AttendanceSessions.Session> planned,
			Set<AttendanceSessionKind> withRows, LocalDate today) {
		List<SessionColumn> columns = new ArrayList<>();
		for (AttendanceSessionKind kind : List.of(AttendanceSessionKind.LESSON, AttendanceSessionKind.WORKSHOP)) {
			AttendanceSessions.Session session = planned.stream()
					.filter(item -> item.kind() == kind)
					.findFirst()
					.orElse(null);
			boolean held = session != null && !session.date().isAfter(today);
			if (held || withRows.contains(kind)) {
				columns.add(new SessionColumn(week, kind, session));
			}
		}
		if (withRows.contains(AttendanceSessionKind.WEEK)) {
			columns.add(new SessionColumn(week, AttendanceSessionKind.WEEK, null));
		}
		return columns;
	}

	private static String key(UUID userId, int week, AttendanceSessionKind kind) {
		return userId + ":" + week + ":" + kind.name();
	}

	/** The number of weeks a program runs: its calendar's, or more when its plan has more. */
	static int totalWeeks(Program program, List<ProgramWeek> plan) {
		LocalDate start = program.getStartDate();
		LocalDate end = program.getEndDate();
		int calendar = start == null || end == null || ProgramCalendar.totalWeeks(start, end) == null ? 0
				: ProgramCalendar.weeksBetween(start, end);
		return Math.max(calendar, plan.stream().mapToInt(ProgramWeek::getWeekNumber).max().orElse(0));
	}

	static @Nullable ProgramWeek plannedWeek(List<ProgramWeek> plan, int week) {
		return plan.stream().filter(item -> item.getWeekNumber() == week).findFirst().orElse(null);
	}

	/** The program's current participants, the people who check in to it and whom a coordinator may mark. */
	Set<UUID> currentParticipantIds(Program program) {
		Set<UUID> ids = new HashSet<>();
		participantsOf(program).stream()
				.filter(Person::current)
				.forEach(person -> ids.add(person.user().getId()));
		return ids;
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
					Texts.forRequest("error.attendanceReportersOnly"));
		}
	}

	private static LocalDate today() {
		return LocalDate.now(MeetingSlotTimes.APP_ZONE);
	}
}
