package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.AttendanceMethod;
import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramAttendance;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramAttendanceRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.util.MeetingSlotTimes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Attendance by session QR code: a coordinator shows a session's code, a participant of that program scans it on
 * the session's day and is recorded once; nobody else, and nothing outside the window. The coordinator reads who
 * attended each session and marks a participant present by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgramAttendanceIntegrationTest {

	private static final String MINE = "/api/programs/mine/attendance";
	private static final String CHECK_IN = "/api/attendance/check-in";
	private static final String REPORT = "/api/coordinator/programs/{id}/attendance";
	private static final String SESSIONS = "/api/coordinator/programs/{id}/attendance/sessions";
	private static final String SESSION = SESSIONS + "/{week}/{kind}";
	private static final String MARK = SESSION + "/participants/{userId}";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JsonMapper jsonMapper;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private OrganizationRepository organizationRepository;
	@Autowired
	private ProgramRepository programRepository;
	@Autowired
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Autowired
	private ProgramStartupRepository programStartupRepository;
	@Autowired
	private ProgramAttendanceRepository attendanceRepository;
	@Autowired
	private ProgramWeekRepository weekRepository;

	private final LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private AppUser founder;
	private AppUser teammate;
	private AppUser otherProgramsMentee;
	private Program program;
	private Program otherProgram;

	/**
	 * Week 1 printed last week, week 2 printed from today with its lesson today (no time: open all day) and its
	 * workshop in two days. The program also runs weeks 3–7 on its calendar.
	 */
	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Koordinatör Test", UserRole.COORDINATOR, null);
		otherCoordinator = user("Başka Koordinatör", UserRole.COORDINATOR, null);
		program = program("Katılım Programı", admin, today.minusDays(7), today.plusDays(40));
		coordinatorOf(program, coordinator);
		otherProgram = program("Başka Program", otherCoordinator, today.minusDays(3), today.plusDays(30));
		week(program, 1, today.minusDays(7), today.minusDays(1));
		week(program, 2, today, today.plusDays(6),
				new ProgramWeekSession(today.plusDays(2), null, null, null, "Workshop: Sarper"));

		Organization team = team("Döngü Katılım", program);
		founder = user("Gülse Buran", UserRole.MENTEE, team);
		teammate = user("Mert Kaya", UserRole.MENTEE, team);
		otherProgramsMentee = user("Zeynep Demir", UserRole.MENTEE, team("Lumen Katılım", otherProgram));
	}

	@Test
	void aParticipantScansTheLessonsCodeOnceAndIsRecordedByQr() throws Exception {
		String token = qrToken(2, AttendanceSessionKind.LESSON);

		checkIn(founder, token).andExpect(status().isOk())
				.andExpect(jsonPath("$.programId").value(program.getId().toString()))
				.andExpect(jsonPath("$.week").value(2))
				.andExpect(jsonPath("$.kind").value("LESSON"))
				.andExpect(jsonPath("$.date").value(today.toString()))
				.andExpect(jsonPath("$.title").value("Hafta 2"))
				.andExpect(jsonPath("$.alreadyRecorded").value(false));
		checkIn(founder, token).andExpect(status().isOk())
				.andExpect(jsonPath("$.alreadyRecorded").value(true));

		assertThat(attendanceRepository.findByProgramId(program.getId())).singleElement().satisfies(row -> {
			assertThat(row.getUserId()).isEqualTo(founder.getId());
			assertThat(row.getWeekNumber()).isEqualTo(2);
			assertThat(row.getSessionKind()).isEqualTo(AttendanceSessionKind.LESSON);
			assertThat(row.getMethod()).isEqualTo(AttendanceMethod.QR);
			assertThat(row.getRecordedBy()).isNull();
		});
		asProxy(get(MINE), founder).andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("OPEN"))
				.andExpect(jsonPath("$.week").value(2))
				.andExpect(jsonPath("$.sessions.length()").value(2))
				.andExpect(jsonPath("$.sessions[0].kind").value("LESSON"))
				.andExpect(jsonPath("$.sessions[0].checkedInAt").exists())
				.andExpect(jsonPath("$.sessions[1].kind").value("WORKSHOP"))
				.andExpect(jsonPath("$.sessions[1].date").value(today.plusDays(2).toString()))
				.andExpect(jsonPath("$.sessions[1].checkedInAt").doesNotExist());
	}

	@Test
	void theCodeStaysTheSameUntilRegeneratedAndTheOldLinkThenStopsWorking() throws Exception {
		String first = qrToken(2, AttendanceSessionKind.LESSON);
		assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
		assertThat(qrToken(2, AttendanceSessionKind.LESSON)).isEqualTo(first);

		String renewed = token(asProxy(post(SESSION + "/qr/regenerate", program.getId(), 2, "LESSON"), coordinator)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.date").value(today.toString())));

		assertThat(renewed).isNotEqualTo(first);
		checkIn(founder, first).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE_QR_NOT_VALID"));
		checkIn(founder, renewed).andExpect(status().isOk());
	}

	@Test
	void anUnknownOrMalformedTokenRecordsNothing() throws Exception {
		checkIn(founder, "A".repeat(43)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE_QR_NOT_VALID"))
				.andExpect(jsonPath("$.message").value("Bu QR şu an geçerli değil."));
		checkIn(founder, "../../etc").andExpect(status().isBadRequest());
		checkIn(founder, "A".repeat(65)).andExpect(status().isBadRequest());
		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
	}

	@Test
	void aCodeWorksOnlyOnItsSessionsDay() throws Exception {
		String workshop = qrToken(2, AttendanceSessionKind.WORKSHOP);
		String lastWeek = qrToken(1, AttendanceSessionKind.LESSON);

		checkIn(founder, workshop).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE_QR_NOT_VALID"))
				.andExpect(jsonPath("$.message").value(containsString(
						today.plusDays(2).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))));
		checkIn(founder, lastWeek).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE_QR_NOT_VALID"));
		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
	}

	@Test
	void onlyApprovedMenteesOfTheCodesProgramCheckIn() throws Exception {
		String token = qrToken(2, AttendanceSessionKind.LESSON);
		AppUser mentor = user("Leyla Aksoy", UserRole.MENTOR, null);

		checkIn(otherProgramsMentee, token).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Bu programın katılımcısı değilsin."));
		checkIn(mentor, token).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Yoklama yalnızca program katılımcıları içindir."));
		checkIn(coordinator, token).andExpect(status().isForbidden());
		teammate.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(teammate);
		checkIn(teammate, token).andExpect(status().isForbidden());

		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
		assertThat(attendanceRepository.findByProgramId(otherProgram.getId())).isEmpty();
	}

	// A signed-in token is always the caller: it cannot check someone else in.
	@Test
	void aSignedInParticipantCannotCheckInSomeoneElse() throws Exception {
		String token = qrToken(2, AttendanceSessionKind.LESSON);

		mockMvc.perform(post(CHECK_IN)
				.with(authentication(signedIn(otherProgramsMentee)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(founder, token)))
				.andExpect(status().isForbidden());

		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
	}

	@Test
	void signedOutCallsAreRefused() throws Exception {
		String token = qrToken(2, AttendanceSessionKind.LESSON);

		mockMvc.perform(post(CHECK_IN).contentType(MediaType.APPLICATION_JSON).content(body(founder, token)))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get(MINE).param("actorId", founder.getId().toString())).andExpect(status().isUnauthorized());
		mockMvc.perform(post(SESSION + "/qr", program.getId(), 2, "LESSON")
				.param("actorId", coordinator.getId().toString()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get(REPORT, program.getId()).param("actorId", coordinator.getId().toString()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void theWeeklySelfCheckInIsGone() throws Exception {
		mockMvc.perform(post(MINE).with(httpBasic("test", "test")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + founder.getId() + "\",\"week\":2}"))
				.andExpect(status().is4xxClientError());
		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
	}

	@Test
	void onlyTheProgramsCoordinatorsAndAdminsManageItsCodes() throws Exception {
		asProxy(get(SESSIONS, program.getId()), admin).andExpect(status().isOk());
		asProxy(post(SESSION + "/qr", program.getId(), 2, "LESSON"), admin).andExpect(status().isOk());
		asProxy(get(SESSIONS, program.getId()), otherCoordinator).andExpect(status().isForbidden());
		asProxy(post(SESSION + "/qr", program.getId(), 2, "LESSON"), otherCoordinator)
				.andExpect(status().isForbidden());
		asProxy(post(SESSION + "/qr/regenerate", program.getId(), 2, "LESSON"), founder)
				.andExpect(status().isForbidden());
		asProxy(put(MARK, program.getId(), 2, "LESSON", founder.getId()), founder)
				.andExpect(status().isForbidden());
		asProxy(delete(MARK, program.getId(), 2, "LESSON", founder.getId()), otherCoordinator)
				.andExpect(status().isForbidden());
		asProxy(get(REPORT, program.getId()), otherCoordinator).andExpect(status().isForbidden());
		asProxy(get(REPORT + "/export", program.getId()), founder).andExpect(status().isForbidden());
	}

	@Test
	void theSessionsListEveryWeeksLessonAndWorkshop() throws Exception {
		checkIn(founder, qrToken(2, AttendanceSessionKind.LESSON)).andExpect(status().isOk());

		asProxy(get(SESSIONS, program.getId()), coordinator).andExpect(status().isOk())
				.andExpect(jsonPath("$.attendanceEnabled").value(true))
				.andExpect(jsonPath("$.programName").value("Katılım Programı"))
				.andExpect(jsonPath("$.sessions[0].week").value(1))
				.andExpect(jsonPath("$.sessions[0].kind").value("LESSON"))
				.andExpect(jsonPath("$.sessions[0].hasQr").value(false))
				.andExpect(jsonPath("$.sessions[1].week").value(2))
				.andExpect(jsonPath("$.sessions[1].kind").value("LESSON"))
				.andExpect(jsonPath("$.sessions[1].hasQr").value(true))
				.andExpect(jsonPath("$.sessions[1].checkedIn").value(1))
				.andExpect(jsonPath("$.sessions[2].kind").value("WORKSHOP"))
				.andExpect(jsonPath("$.sessions[2].title").value("Workshop: Sarper"))
				.andExpect(jsonPath("$.sessions[3].week").value(3));
	}

	@Test
	void aSessionThePlanDoesNotHaveHasNoCode() throws Exception {
		asProxy(post(SESSION + "/qr", program.getId(), 1, "WORKSHOP"), coordinator)
				.andExpect(status().isNotFound());
		asProxy(post(SESSION + "/qr", program.getId(), 2, "WEEK"), coordinator).andExpect(status().isNotFound());
		asProxy(post(SESSION + "/qr", program.getId(), 99, "LESSON"), coordinator).andExpect(status().isNotFound());
		asProxy(post(SESSION + "/qr", program.getId(), 0, "LESSON"), coordinator).andExpect(status().isNotFound());
		asProxy(post(SESSION + "/qr", program.getId(), 2, "lesson"), coordinator)
				.andExpect(status().isBadRequest());
	}

	@Test
	void aProgramWithAttendanceOffTakesNoneButKeepsItsReport() throws Exception {
		String token = qrToken(2, AttendanceSessionKind.LESSON);
		legacy(founder, 1);
		settings(coordinator, false).andExpect(status().isOk())
				.andExpect(jsonPath("$.rules.attendanceEnabled").value(false));

		checkIn(founder, token).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE_OFF"))
				.andExpect(jsonPath("$.message").value("Bu programda yoklama alınmıyor."));
		asProxy(post(SESSION + "/qr", program.getId(), 2, "LESSON"), coordinator)
				.andExpect(status().isConflict());
		asProxy(put(MARK, program.getId(), 2, "LESSON", teammate.getId()), coordinator)
				.andExpect(status().isConflict());
		asProxy(get(MINE), founder).andExpect(jsonPath("$.state").value("DISABLED"))
				.andExpect(jsonPath("$.sessions.length()").value(0));
		asProxy(get(REPORT, program.getId()), coordinator).andExpect(status().isOk())
				.andExpect(jsonPath("$.attendanceEnabled").value(false))
				.andExpect(jsonPath("$.sessions[1].kind").value("WEEK"))
				.andExpect(jsonPath("$.sessions[1].checkedIn").value(1));

		settings(coordinator, true).andExpect(jsonPath("$.rules.attendanceEnabled").value(true));
		checkIn(founder, token).andExpect(status().isOk());
	}

	@Test
	void onlyTheProgramsCoordinatorsTurnAttendanceOff() throws Exception {
		settings(admin, false).andExpect(status().isBadRequest());
		settings(otherCoordinator, false).andExpect(status().isBadRequest());
		assertThat(programRepository.findById(program.getId())).get()
				.extracting(Program::isAttendanceEnabled).isEqualTo(true);
	}

	@Test
	void anArchivedProgramTakesNoAttendanceButKeepsItsReport() throws Exception {
		String token = qrToken(2, AttendanceSessionKind.LESSON);
		checkIn(founder, token).andExpect(status().isOk());
		program.setStatus(ProgramStatus.COMPLETED);
		programRepository.saveAndFlush(program);

		checkIn(teammate, token).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE_QR_NOT_VALID"));
		asProxy(post(SESSION + "/qr", program.getId(), 2, "LESSON"), coordinator)
				.andExpect(status().isBadRequest());
		asProxy(get(MINE), founder).andExpect(jsonPath("$.state").value("UNSCHEDULED"));
		asProxy(get(REPORT, program.getId()), coordinator).andExpect(status().isOk())
				.andExpect(jsonPath("$.sessions[1].checkedIn").value(1));
	}

	@Test
	void aCoordinatorMarksAParticipantPresentByHandAndRemovesIt() throws Exception {
		asProxy(put(MARK, program.getId(), 2, "LESSON", teammate.getId()), coordinator)
				.andExpect(status().isNoContent());
		asProxy(put(MARK, program.getId(), 2, "LESSON", teammate.getId()), coordinator)
				.andExpect(status().isNoContent());

		assertThat(attendanceRepository.findByProgramId(program.getId())).singleElement().satisfies(row -> {
			assertThat(row.getMethod()).isEqualTo(AttendanceMethod.MANUAL);
			assertThat(row.getRecordedBy()).isEqualTo(coordinator.getId());
		});
		asProxy(get(REPORT, program.getId()), coordinator)
				.andExpect(jsonPath("$.participants[1].name").value("Mert Kaya"))
				.andExpect(jsonPath("$.participants[1].marks[1].attended").value(true))
				.andExpect(jsonPath("$.participants[1].marks[1].method").value("MANUAL"));

		asProxy(delete(MARK, program.getId(), 2, "LESSON", teammate.getId()), coordinator)
				.andExpect(status().isNoContent());
		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
	}

	@Test
	void aCoordinatorCannotMarkAFutureSessionOrSomeoneOutsideTheProgram() throws Exception {
		asProxy(put(MARK, program.getId(), 2, "WORKSHOP", teammate.getId()), coordinator)
				.andExpect(status().isBadRequest());
		asProxy(put(MARK, program.getId(), 2, "LESSON", otherProgramsMentee.getId()), coordinator)
				.andExpect(status().isBadRequest());
		asProxy(put(MARK, program.getId(), 2, "LESSON", UUID.randomUUID()), coordinator)
				.andExpect(status().isBadRequest());
		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
	}

	@Test
	void theReportHasAColumnPerHeldSessionWithTheWeeklyCheckInsBeside() throws Exception {
		legacy(founder, 1);
		checkIn(founder, qrToken(2, AttendanceSessionKind.LESSON)).andExpect(status().isOk());

		asProxy(get(REPORT, program.getId()), coordinator)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attendanceEnabled").value(true))
				// Week 2's workshop is in two days, so it has no column yet.
				.andExpect(jsonPath("$.sessions.length()").value(3))
				.andExpect(jsonPath("$.sessions[0].week").value(1))
				.andExpect(jsonPath("$.sessions[0].kind").value("LESSON"))
				.andExpect(jsonPath("$.sessions[0].date").value(today.minusDays(7).toString()))
				.andExpect(jsonPath("$.sessions[0].rate").value(0))
				.andExpect(jsonPath("$.sessions[1].kind").value("WEEK"))
				.andExpect(jsonPath("$.sessions[1].date").doesNotExist())
				.andExpect(jsonPath("$.sessions[1].rate").value(50))
				.andExpect(jsonPath("$.sessions[2].week").value(2))
				.andExpect(jsonPath("$.sessions[2].kind").value("LESSON"))
				.andExpect(jsonPath("$.sessions[2].checkedIn").value(1))
				.andExpect(jsonPath("$.sessions[2].participants").value(2))
				.andExpect(jsonPath("$.participants.length()").value(2))
				.andExpect(jsonPath("$.participants[0].name").value("Gülse Buran"))
				.andExpect(jsonPath("$.participants[0].userId").value(founder.getId().toString()))
				.andExpect(jsonPath("$.participants[0].current").value(true))
				.andExpect(jsonPath("$.participants[0].teamName").value("Döngü Katılım"))
				.andExpect(jsonPath("$.participants[0].marks[0].attended").value(false))
				.andExpect(jsonPath("$.participants[0].marks[1].method").value("SELF"))
				.andExpect(jsonPath("$.participants[0].marks[2].method").value("QR"))
				.andExpect(jsonPath("$.participants[0].attendedSessions").value(2))
				.andExpect(jsonPath("$.participants[?(@.name == 'Zeynep Demir')]").isEmpty());
	}

	@Test
	void theDateRangeNarrowsTheWeeks() throws Exception {
		asProxy(get(REPORT, program.getId()).param("from", today.toString()).param("to", today.toString()),
				coordinator)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessions.length()").value(1))
				.andExpect(jsonPath("$.sessions[0].week").value(2));
		asProxy(get(REPORT, program.getId()).param("from", today.toString())
				.param("to", today.minusDays(1).toString()), coordinator)
				.andExpect(status().isBadRequest());
	}

	@Test
	void anUpcomingProgramHasAnEmptyReportNotAnError() throws Exception {
		Program later = program("Gelecek Program", admin, today.plusDays(5), today.plusDays(60));
		AppUser early = user("Erken Katılımcı", UserRole.MENTEE, team("Erken Takım", later));

		asProxy(get(MINE), early).andExpect(jsonPath("$.state").value("NOT_STARTED"));
		asProxy(get(REPORT, later.getId()), admin)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessions.length()").value(0))
				.andExpect(jsonPath("$.participants.length()").value(0));
	}

	@Test
	void someoneOutsideAnyProgramHasNoAttendance() throws Exception {
		AppUser mentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		asProxy(get(MINE), mentor).andExpect(jsonPath("$.state").value("NO_PROGRAM"));
		teammate.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(teammate);
		asProxy(get(MINE), teammate).andExpect(status().isForbidden());
	}

	@Test
	void countsAPersonalTeamStudentButNotAMenteeWithoutATeam() throws Exception {
		Organization personal = new Organization();
		personal.setName("Kişisel");
		personal.setPersonal(true);
		personal = organizationRepository.saveAndFlush(personal);
		AppUser student = user("Ayşe Yılmaz", UserRole.MENTEE, personal);
		student.setProgramId(program.getId());
		userRepository.saveAndFlush(student);
		personal.setFounder(student);
		organizationRepository.saveAndFlush(personal);
		AppUser teamless = user("Takımsız Kişi", UserRole.MENTEE, null);
		teamless.setProgramId(program.getId());
		userRepository.saveAndFlush(teamless);

		checkIn(student, qrToken(2, AttendanceSessionKind.LESSON)).andExpect(status().isOk());
		asProxy(get(REPORT, program.getId()), coordinator)
				.andExpect(jsonPath("$.participants[?(@.name == 'Ayşe Yılmaz')].attendedSessions")
						.value(Matchers.contains(1)))
				.andExpect(jsonPath("$.participants[?(@.name == 'Ayşe Yılmaz')].teamName")
						.value(Matchers.contains(Matchers.nullValue())))
				.andExpect(jsonPath("$.participants[?(@.name == 'Takımsız Kişi')]").isEmpty());
	}

	// Someone who attended and then moved to another program's team keeps that session, without the old team,
	// and does not lower the rate of the sessions after they left; nor can they be marked here any more.
	@Test
	void aParticipantWhoLeftKeepsTheirSessionWithoutLoweringTheRest() throws Exception {
		Program third = program("Üçüncü Program", admin, today.minusDays(30), today.plusDays(30));
		asProxy(put(MARK, program.getId(), 1, "LESSON", teammate.getId()), coordinator)
				.andExpect(status().isNoContent());
		teammate.setOrganization(team("Yeni Takım", third));
		userRepository.saveAndFlush(teammate);
		checkIn(founder, qrToken(2, AttendanceSessionKind.LESSON)).andExpect(status().isOk());

		asProxy(get(REPORT, program.getId()), coordinator)
				.andExpect(jsonPath("$.sessions[0].participants").value(2))
				.andExpect(jsonPath("$.sessions[0].rate").value(50))
				.andExpect(jsonPath("$.sessions[1].participants").value(1))
				.andExpect(jsonPath("$.sessions[1].rate").value(100))
				.andExpect(jsonPath("$.participants[1].name").value("Mert Kaya"))
				.andExpect(jsonPath("$.participants[1].current").value(false))
				.andExpect(jsonPath("$.participants[1].teamName").doesNotExist());
		asProxy(put(MARK, program.getId(), 2, "LESSON", teammate.getId()), coordinator)
				.andExpect(status().isBadRequest());
	}

	@Test
	void exportsTheReportPerSessionAsExcelOrCsv() throws Exception {
		legacy(founder, 1);
		checkIn(founder, qrToken(2, AttendanceSessionKind.LESSON)).andExpect(status().isOk());

		asProxy(get(REPORT + "/export", program.getId()), coordinator)
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type",
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
		String csv = csv();
		assertThat(csv).contains("Ad Soyad,E-posta,Takım,Hafta 1 Eğitim (", "Hafta 1 Haftalık (eski)",
				"Hafta 2 Eğitim (", "Katıldığı oturum");
		assertThat(csv).contains("Gülse Buran,").contains("Hayır,Evet (haftalık),Evet (QR),2");
		assertThat(csv).contains("Katılım oranı,,,%0,%50,%50,");
	}

	@Test
	void aNameThatLooksLikeAFormulaStaysTextInTheCsv() throws Exception {
		founder.setFullName("=HYPERLINK(\"x\")");
		userRepository.saveAndFlush(founder);

		assertThat(csv()).contains("\"'=HYPERLINK(\"\"x\"\")\"");
	}

	@Test
	void theTokenOnlyGoesToTheCoordinatorAskingForTheCode() throws Exception {
		String token = qrToken(2, AttendanceSessionKind.LESSON);

		asProxy(get(SESSIONS, program.getId()), coordinator).andExpect(content().string(not(containsString(token))));
		asProxy(get(MINE), founder).andExpect(content().string(not(containsString(token))));
		checkIn(founder, token).andExpect(content().string(not(containsString(token))));
	}

	private String csv() throws Exception {
		return new String(mockMvc.perform(get(REPORT + "/export", program.getId())
				.param("format", "CSV")
				.param("actorId", coordinator.getId().toString())
				.with(httpBasic("test", "test")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	private String qrToken(int week, AttendanceSessionKind kind) throws Exception {
		return token(asProxy(post(SESSION + "/qr", program.getId(), week, kind.name()), coordinator)
				.andExpect(status().isOk()));
	}

	private String token(ResultActions result) throws Exception {
		JsonNode json = jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
		return json.get("token").asString();
	}

	private ResultActions checkIn(AppUser actor, String token) throws Exception {
		return mockMvc.perform(post(CHECK_IN)
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(actor, token)));
	}

	private ResultActions settings(AppUser actor, boolean attendanceEnabled) throws Exception {
		return mockMvc.perform(put("/api/coordinator/programs/{id}", program.getId())
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + actor.getId() + "\",\"name\":\"Katılım Programı\",\"startDate\":\""
						+ program.getStartDate() + "\",\"endDate\":\"" + program.getEndDate()
						+ "\",\"attendanceEnabled\":" + attendanceEnabled + "}"));
	}

	private static String body(AppUser actor, String token) {
		return "{\"actorId\":\"" + actor.getId() + "\",\"token\":\"" + token + "\"}";
	}

	private ResultActions asProxy(MockHttpServletRequestBuilder request, AppUser actor) throws Exception {
		return mockMvc.perform(request.param("actorId", actor.getId().toString()).with(httpBasic("test", "test")));
	}

	private void legacy(AppUser person, int week) {
		ProgramAttendance row = new ProgramAttendance();
		row.setId(UUID.randomUUID());
		row.setProgramId(program.getId());
		row.setUserId(person.getId());
		row.setWeekNumber(week);
		row.setSessionKind(AttendanceSessionKind.WEEK);
		row.setMethod(AttendanceMethod.SELF);
		row.setCheckedInAt(Instant.now());
		attendanceRepository.saveAndFlush(row);
	}

	private void week(Program owner, int number, LocalDate start, LocalDate end, ProgramWeekSession... sessions) {
		ProgramWeek week = new ProgramWeek();
		week.setId(UUID.randomUUID());
		week.setProgramId(owner.getId());
		week.setWeekNumber(number);
		week.setTitle("Hafta " + number);
		week.setStartDate(start);
		week.setEndDate(end);
		week.setSessions(new ArrayList<>(List.of(sessions)));
		week.setUpdatedAt(Instant.now());
		weekRepository.saveAndFlush(week);
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@attendance.test");
		user.setRole(role);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}

	private Organization team(String name, Program joined) {
		Organization organization = new Organization();
		organization.setName(name);
		Organization saved = organizationRepository.saveAndFlush(organization);
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(joined.getId());
		startup.setOrganizationId(saved.getId());
		startup.setCreatedAt(Instant.now());
		programStartupRepository.saveAndFlush(startup);
		return saved;
	}

	private Program program(String name, AppUser createdBy, LocalDate start, LocalDate end) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(createdBy.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		created.setStartDate(start);
		created.setEndDate(end);
		return programRepository.saveAndFlush(created);
	}

	private void coordinatorOf(Program coordinated, AppUser user) {
		ProgramCoordinator link = new ProgramCoordinator();
		link.setId(UUID.randomUUID());
		link.setProgramId(coordinated.getId());
		link.setUserId(user.getId());
		link.setAddedBy(admin.getId());
		link.setCreatedAt(Instant.now());
		programCoordinatorRepository.saveAndFlush(link);
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "attendance-test-uid"), null,
				List.of());
	}
}
