package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AttendanceMethod;
import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramAttendanceSessionsResponse;
import com.dps.teknokent.dto.responses.SessionCheckInResponse;
import com.dps.teknokent.dto.responses.SessionQrResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramAttendance;
import com.dps.teknokent.entity.ProgramSessionQr;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramAttendanceRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramSessionQrRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.AttendanceSessions;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramSchedule;
import com.dps.teknokent.util.RandomTokens;
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;

/**
 * Attendance by session QR code. Each lesson and workshop of a program week has a QR code whose link carries an
 * unguessable token; a participant who scans it while the session's check-in is open ({@link AttendanceSessions})
 * is recorded once for that session. The program's coordinators show, print and regenerate the codes, and mark a
 * participant present by hand (a phone that did not scan). Nothing is recorded for anyone but a current
 * participant of the code's program, outside the session's window, or in a program that does not take attendance.
 */
@Slf4j
@Service
public class ProgramSessionAttendanceService {

	/** 32 random bytes: 43 base64url characters, far beyond guessing. */
	static final int TOKEN_BYTES = 32;

	/** The client branches on these codes; the message says the same in the reader's language. */
	static final String CODE_QR_NOT_VALID = "ATTENDANCE_QR_NOT_VALID";
	static final String CODE_ATTENDANCE_OFF = "ATTENDANCE_OFF";
	static final String CODE_ALREADY_RECORDED = "ATTENDANCE_ALREADY_RECORDED";

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private final DomainLookupService domainLookupService;
	private final MenteeProgramService menteeProgramService;
	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramAttendanceService attendanceService;
	private final ProgramRepository programRepository;
	private final ProgramWeekRepository programWeeks;
	private final ProgramAttendanceRepository attendanceRepository;
	private final ProgramSessionQrRepository qrRepository;
	private final Clock clock;

	@Autowired
	public ProgramSessionAttendanceService(DomainLookupService domainLookupService,
			MenteeProgramService menteeProgramService, CoordinatorProgramService coordinatorProgramService,
			ProgramAttendanceService attendanceService, ProgramRepository programRepository,
			ProgramWeekRepository programWeeks, ProgramAttendanceRepository attendanceRepository,
			ProgramSessionQrRepository qrRepository) {
		this(domainLookupService, menteeProgramService, coordinatorProgramService, attendanceService,
				programRepository, programWeeks, attendanceRepository, qrRepository, Clock.systemUTC());
	}

	ProgramSessionAttendanceService(DomainLookupService domainLookupService,
			MenteeProgramService menteeProgramService, CoordinatorProgramService coordinatorProgramService,
			ProgramAttendanceService attendanceService, ProgramRepository programRepository,
			ProgramWeekRepository programWeeks, ProgramAttendanceRepository attendanceRepository,
			ProgramSessionQrRepository qrRepository, Clock clock) {
		this.domainLookupService = domainLookupService;
		this.menteeProgramService = menteeProgramService;
		this.coordinatorProgramService = coordinatorProgramService;
		this.attendanceService = attendanceService;
		this.programRepository = programRepository;
		this.programWeeks = programWeeks;
		this.attendanceRepository = attendanceRepository;
		this.qrRepository = qrRepository;
		this.clock = clock;
	}

	/**
	 * Records the caller at the session whose QR code carries {@code token}. A token that is unknown or was
	 * regenerated, a program that is not running, and a session the plan no longer has all answer the same 409
	 * ("this QR is not valid now"), so a stale or guessed link learns nothing about the program.
	 */
	@Transactional
	public SessionCheckInResponse checkIn(UUID actorId, String token) {
		ProgramSessionQr qr = qrRepository.findByToken(token).orElseThrow(ProgramSessionAttendanceService::notValid);
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!actor.has(UserRole.MENTEE)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.attendanceMenteesOnly"));
		}
		if (actor.getStatus() != UserAccountStatus.APPROVED
				|| menteeProgramService.programOf(actor).filter(qr.getProgramId()::equals).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					Texts.forRequest("error.attendanceNotParticipant"));
		}
		Program program = programRepository.findById(qr.getProgramId())
				.orElseThrow(ProgramSessionAttendanceService::notValid);
		if (!program.isAttendanceEnabled()) {
			throw attendanceOff();
		}
		AttendanceSessions.Session session = program.getStatus() == ProgramStatus.ACTIVE
				? sessionOf(program, qr.getWeekNumber(), qr.getSessionKind())
				: null;
		if (session == null) {
			throw notValid();
		}
		// Someone already recorded sees that, even when they scan again after the window has closed.
		ProgramAttendance existing = attendanceRepository.findByProgramIdAndUserIdAndWeekNumberAndSessionKind(
				program.getId(), actor.getId(), session.week(), session.kind()).orElse(null);
		if (existing != null) {
			return checkedIn(program, session, existing.getCheckedInAt(), true);
		}
		Instant now = clock.instant();
		if (!session.isOpenAt(now)) {
			throw notOpen(session);
		}
		try {
			attendanceRepository.saveAndFlush(row(program, actor.getId(), session, AttendanceMethod.QR, null, now));
		} catch (DataIntegrityViolationException raced) {
			throw new ConflictException(Texts.forRequest("error.attendanceAlreadyRecorded"), CODE_ALREADY_RECORDED);
		}
		return checkedIn(program, session, now, false);
	}

	/**
	 * Every session of the program's weeks that takes attendance, whether its QR code was made yet and how many
	 * attended it. For the program's coordinators and admins.
	 */
	@Transactional(readOnly = true)
	public ProgramAttendanceSessionsResponse sessions(UUID actorId, UUID programId) {
		Program program = requireCoordinator(actorId, programId);
		List<ProgramWeek> plan = programWeeks.findByProgramIdOrderByWeekNumberAsc(programId);
		ProgramSchedule schedule = ProgramSchedule.of(program.getStartDate(), program.getEndDate(), plan);
		Set<String> withQr = new HashSet<>();
		qrRepository.findByProgramId(programId)
				.forEach(qr -> withQr.add(key(qr.getWeekNumber(), qr.getSessionKind())));
		Map<String, Integer> counts = new HashMap<>();
		attendanceRepository.findByProgramId(programId)
				.forEach(row -> counts.merge(key(row.getWeekNumber(), row.getSessionKind()), 1, Integer::sum));
		List<ProgramAttendanceSessionsResponse.Session> sessions = IntStream
				.rangeClosed(1, ProgramAttendanceService.totalWeeks(program, plan))
				.boxed()
				.flatMap(week -> AttendanceSessions
						.of(schedule, ProgramAttendanceService.plannedWeek(plan, week), week).stream())
				.map(session -> new ProgramAttendanceSessionsResponse.Session(session.week(), session.kind(),
						session.date(), session.startTime(), session.endTime(), session.title(),
						withQr.contains(key(session.week(), session.kind())),
						counts.getOrDefault(key(session.week(), session.kind()), 0)))
				.toList();
		return new ProgramAttendanceSessionsResponse(program.getId(), program.getName(),
				program.isAttendanceEnabled(), sessions);
	}

	/** The session's QR code, made the first time it is asked for. */
	@Transactional
	public SessionQrResponse qr(UUID actorId, UUID programId, int week, AttendanceSessionKind kind) {
		Program program = requireWritable(actorId, programId);
		AttendanceSessions.Session session = requireSession(program, week, kind);
		ProgramSessionQr qr = qrRepository.findByProgramIdAndWeekNumberAndSessionKind(programId, week, kind)
				.orElseGet(() -> qrRepository.saveAndFlush(newToken(newQr(programId, week, kind), actorId)));
		return qrOf(program, session, qr);
	}

	/** Replaces the session's QR code; the old link stops working at once. */
	@Transactional
	public SessionQrResponse regenerate(UUID actorId, UUID programId, int week, AttendanceSessionKind kind) {
		Program program = requireWritable(actorId, programId);
		AttendanceSessions.Session session = requireSession(program, week, kind);
		ProgramSessionQr qr = newToken(qrRepository.findByProgramIdAndWeekNumberAndSessionKind(programId, week, kind)
				.orElseGet(() -> newQr(programId, week, kind)), actorId);
		qrRepository.saveAndFlush(qr);
		log.info("Session QR regenerated: program {} week {} {} by {}", programId, week, kind, actorId);
		return qrOf(program, session, qr);
	}

	/**
	 * A coordinator marks a current participant present at a session that has taken place (today or earlier).
	 * Marking someone already recorded changes nothing.
	 */
	@Transactional
	public void markPresent(UUID actorId, UUID programId, int week, AttendanceSessionKind kind, UUID userId) {
		Program program = requireWritable(actorId, programId);
		AttendanceSessions.Session session = requireSession(program, week, kind);
		if (session.date().isAfter(LocalDate.ofInstant(clock.instant(), MeetingSlotTimes.APP_ZONE))) {
			throw new InvalidRequestException(Texts.forRequest("error.attendanceNotHeldYet"));
		}
		if (!attendanceService.currentParticipantIds(program).contains(userId)) {
			throw new InvalidRequestException(Texts.forRequest("error.attendanceNotAParticipant"));
		}
		if (attendanceRepository
				.findByProgramIdAndUserIdAndWeekNumberAndSessionKind(programId, userId, week, kind).isPresent()) {
			return;
		}
		attendanceRepository.saveAndFlush(
				row(program, userId, session, AttendanceMethod.MANUAL, actorId, clock.instant()));
	}

	/**
	 * A coordinator removes a participant's attendance at a session, whichever way it was recorded, a weekly
	 * self-check-in from before per-session attendance included.
	 */
	@Transactional
	public void unmark(UUID actorId, UUID programId, int week, AttendanceSessionKind kind, UUID userId) {
		requireWritable(actorId, programId);
		attendanceRepository.findByProgramIdAndUserIdAndWeekNumberAndSessionKind(programId, userId, week, kind)
				.ifPresent(attendanceRepository::delete);
	}

	private Program requireCoordinator(UUID actorId, UUID programId) {
		try {
			return coordinatorProgramService.requireManagedProgram(actorId, programId);
		} catch (InvalidRequestException refused) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					Texts.forRequest("error.attendanceReportersOnly"));
		}
	}

	/** The program's coordinators and admins, on a program that is not archived. */
	private Program requireWritable(UUID actorId, UUID programId) {
		Program program = requireCoordinator(actorId, programId);
		if (program.getStatus() == ProgramStatus.COMPLETED) {
			throw new InvalidRequestException(Texts.forRequest("error.attendanceProgramArchived"));
		}
		return program;
	}

	/** The session, in a program that takes attendance; WEEK is never a session of its own. */
	private AttendanceSessions.Session requireSession(Program program, int week, AttendanceSessionKind kind) {
		if (!program.isAttendanceEnabled()) {
			throw attendanceOff();
		}
		AttendanceSessions.Session session = sessionOf(program, week, kind);
		if (session == null) {
			throw new ResourceNotFoundException(Texts.forRequest("error.attendanceNoSession"));
		}
		return session;
	}

	private AttendanceSessions.@Nullable Session sessionOf(Program program, int week, AttendanceSessionKind kind) {
		List<ProgramWeek> plan = programWeeks.findByProgramIdOrderByWeekNumberAsc(program.getId());
		if (week < 1 || week > ProgramAttendanceService.totalWeeks(program, plan)) {
			return null;
		}
		return AttendanceSessions.find(ProgramSchedule.of(program.getStartDate(), program.getEndDate(), plan),
				ProgramAttendanceService.plannedWeek(plan, week), week, kind);
	}

	private static ProgramSessionQr newQr(UUID programId, int week, AttendanceSessionKind kind) {
		ProgramSessionQr qr = new ProgramSessionQr();
		qr.setId(UUID.randomUUID());
		qr.setProgramId(programId);
		qr.setWeekNumber(week);
		qr.setSessionKind(kind);
		return qr;
	}

	private ProgramSessionQr newToken(ProgramSessionQr qr, UUID actorId) {
		qr.setToken(RandomTokens.urlSafe(TOKEN_BYTES));
		qr.setCreatedAt(clock.instant());
		qr.setCreatedBy(actorId);
		return qr;
	}

	private static ProgramAttendance row(Program program, UUID userId, AttendanceSessions.Session session,
			AttendanceMethod method, @Nullable UUID recordedBy, Instant at) {
		ProgramAttendance row = new ProgramAttendance();
		row.setId(UUID.randomUUID());
		row.setProgramId(program.getId());
		row.setUserId(userId);
		row.setWeekNumber(session.week());
		row.setSessionKind(session.kind());
		row.setMethod(method);
		row.setRecordedBy(recordedBy);
		row.setCheckedInAt(at);
		return row;
	}

	private static SessionQrResponse qrOf(Program program, AttendanceSessions.Session session, ProgramSessionQr qr) {
		return new SessionQrResponse(program.getId(), program.getName(), session.week(), session.kind(),
				session.date(), session.startTime(), session.endTime(), session.title(), qr.getToken(),
				session.opensAt(), session.closesAt());
	}

	private static SessionCheckInResponse checkedIn(Program program, AttendanceSessions.Session session,
			Instant checkedInAt, boolean alreadyRecorded) {
		return new SessionCheckInResponse(program.getId(), program.getName(), session.week(), session.kind(),
				session.date(), session.startTime(), session.endTime(), session.title(), checkedInAt,
				alreadyRecorded);
	}

	private static ConflictException attendanceOff() {
		return new ConflictException(Texts.forRequest("error.attendanceOff"), CODE_ATTENDANCE_OFF);
	}

	private static ConflictException notValid() {
		return new ConflictException(Texts.forRequest("error.attendanceQrNotValid"), CODE_QR_NOT_VALID);
	}

	/** Not valid now, and when it will be; only a participant of the code's program gets this far. */
	private static ConflictException notOpen(AttendanceSessions.Session session) {
		String day = DAY.format(session.date());
		if (session.startTime() == null) {
			return new ConflictException(Texts.forRequest("error.attendanceQrOpensOn", day), CODE_QR_NOT_VALID);
		}
		LocalTime opens = session.opensAt().atZone(MeetingSlotTimes.APP_ZONE).toLocalTime();
		LocalTime closes = session.closesAt().atZone(MeetingSlotTimes.APP_ZONE).toLocalTime();
		return new ConflictException(
				Texts.forRequest("error.attendanceQrOpensBetween", day, TIME.format(opens), TIME.format(closes)),
				CODE_QR_NOT_VALID);
	}

	private static String key(int week, AttendanceSessionKind kind) {
		return week + ":" + kind.name();
	}
}
