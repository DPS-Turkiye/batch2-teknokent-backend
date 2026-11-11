package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramAttendanceRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.util.MeetingSlotTimes;

/** A participant checks in once for the running week of their own program; its coordinators read who did. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgramAttendanceIntegrationTest {

	private static final String MINE = "/api/programs/mine/attendance";
	private static final String REPORT = "/api/coordinator/programs/{id}/attendance";

	@Autowired
	private MockMvc mockMvc;
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

	private final LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private AppUser founder;
	private AppUser teammate;
	private AppUser otherProgramsMentee;
	private Program program;
	private Program otherProgram;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Koordinatör Test", UserRole.COORDINATOR, null);
		otherCoordinator = user("Başka Koordinatör", UserRole.COORDINATOR, null);
		// Started 10 days ago: week 2 is running, week 1 is over, week 3 has not begun.
		program = program("Katılım Programı", admin, today.minusDays(10), today.plusDays(40));
		coordinatorOf(program, coordinator);
		otherProgram = program("Başka Program", otherCoordinator, today.minusDays(3), today.plusDays(30));

		Organization team = team("Döngü Katılım", program);
		founder = user("Gülse Buran", UserRole.MENTEE, team);
		teammate = user("Mert Kaya", UserRole.MENTEE, team);
		otherProgramsMentee = user("Zeynep Demir", UserRole.MENTEE, team("Lumen Katılım", otherProgram));
	}

	@Test
	void aParticipantSeesTheRunningWeekOfTheirProgram() throws Exception {
		asProxy(get(MINE), founder)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.programId").value(program.getId().toString()))
				.andExpect(jsonPath("$.state").value("OPEN"))
				.andExpect(jsonPath("$.week").value(2))
				.andExpect(jsonPath("$.weekStart").value(today.minusDays(10).plusDays(7).toString()))
				.andExpect(jsonPath("$.weekEnd").value(today.minusDays(10).plusDays(13).toString()))
				.andExpect(jsonPath("$.checkedInAt").doesNotExist());
	}

	@Test
	void checksInOnceForTheRunningWeek() throws Exception {
		checkIn(founder, 2).andExpect(status().isCreated())
				.andExpect(jsonPath("$.state").value("CHECKED_IN"))
				.andExpect(jsonPath("$.checkedInAt").exists());
		asProxy(get(MINE), founder).andExpect(jsonPath("$.state").value("CHECKED_IN"));

		checkIn(founder, 2).andExpect(status().isConflict());
		assertThat(attendanceRepository.findByProgramId(program.getId())).hasSize(1);
	}

	@Test
	void refusesAFutureOrAPastWeek() throws Exception {
		checkIn(founder, 3).andExpect(status().isBadRequest());
		checkIn(founder, 1).andExpect(status().isBadRequest());
		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
	}

	@Test
	void refusesBeforeTheProgramStarts() throws Exception {
		Program later = program("Gelecek Program", admin, today.plusDays(3), today.plusDays(60));
		AppUser early = user("Erken Katılımcı", UserRole.MENTEE, team("Erken Takım", later));

		asProxy(get(MINE), early).andExpect(jsonPath("$.state").value("NOT_STARTED"));
		checkIn(early, 1).andExpect(status().isConflict());
		assertThat(attendanceRepository.findByProgramId(later.getId())).isEmpty();
	}

	// A signed-in token is always the caller: it cannot check someone else in.
	@Test
	void aSignedInParticipantCannotCheckInSomeoneElse() throws Exception {
		mockMvc.perform(post(MINE)
						.with(authentication(signedIn(otherProgramsMentee)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"actorId\":\"" + founder.getId() + "\",\"week\":1}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.programId").value(otherProgram.getId().toString()));

		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
		assertThat(attendanceRepository.findByProgramId(otherProgram.getId()))
				.extracting(row -> row.getUserId())
				.containsExactly(otherProgramsMentee.getId());
	}

	@Test
	void onlyApprovedParticipantsCheckIn() throws Exception {
		AppUser mentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		asProxy(get(MINE), mentor).andExpect(jsonPath("$.state").value("NO_PROGRAM"));
		checkIn(mentor, 2).andExpect(status().isForbidden());

		teammate.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(teammate);
		checkIn(teammate, 2).andExpect(status().isForbidden());
		asProxy(get(MINE), teammate).andExpect(status().isForbidden());
		assertThat(attendanceRepository.findByProgramId(program.getId())).isEmpty();
	}

	@Test
	void signedOutCallsAreRefused() throws Exception {
		mockMvc.perform(get(MINE).param("actorId", founder.getId().toString())).andExpect(status().isUnauthorized());
		mockMvc.perform(post(MINE).contentType(MediaType.APPLICATION_JSON)
						.content("{\"actorId\":\"" + founder.getId() + "\",\"week\":2}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get(REPORT, program.getId()).param("actorId", coordinator.getId().toString()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void theCoordinatorSeesEachWeekPerParticipantWithTheRate() throws Exception {
		checkIn(founder, 2).andExpect(status().isCreated());

		asProxy(get(REPORT, program.getId()), coordinator)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.weeks.length()").value(2))
				.andExpect(jsonPath("$.weeks[0].number").value(1))
				.andExpect(jsonPath("$.weeks[0].rate").value(0))
				.andExpect(jsonPath("$.weeks[1].number").value(2))
				.andExpect(jsonPath("$.weeks[1].checkedIn").value(1))
				.andExpect(jsonPath("$.weeks[1].participants").value(2))
				.andExpect(jsonPath("$.weeks[1].rate").value(50))
				.andExpect(jsonPath("$.participants.length()").value(2))
				.andExpect(jsonPath("$.participants[0].name").value("Gülse Buran"))
				.andExpect(jsonPath("$.participants[0].teamName").value("Döngü Katılım"))
				.andExpect(jsonPath("$.participants[0].attended[0]").value(false))
				.andExpect(jsonPath("$.participants[0].attended[1]").value(true))
				.andExpect(jsonPath("$.participants[0].attendedWeeks").value(1))
				.andExpect(jsonPath("$.participants[1].name").value("Mert Kaya"))
				.andExpect(jsonPath("$.participants[?(@.name == 'Zeynep Demir')]").isEmpty());
	}

	@Test
	void theDateRangeNarrowsTheWeeks() throws Exception {
		LocalDate secondWeek = today.minusDays(10).plusDays(7);
		mockMvc.perform(get(REPORT, program.getId())
						.param("from", secondWeek.toString())
						.param("to", today.toString())
						.param("actorId", coordinator.getId().toString())
						.with(httpBasic("test", "test")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.weeks.length()").value(1))
				.andExpect(jsonPath("$.weeks[0].number").value(2));
		mockMvc.perform(get(REPORT, program.getId())
						.param("from", today.toString())
						.param("to", today.minusDays(1).toString())
						.param("actorId", coordinator.getId().toString())
						.with(httpBasic("test", "test")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void onlyTheProgramsCoordinatorsAndAdminsReadTheReport() throws Exception {
		asProxy(get(REPORT, program.getId()), admin).andExpect(status().isOk());
		asProxy(get(REPORT, program.getId()), otherCoordinator).andExpect(status().isForbidden());
		asProxy(get(REPORT, program.getId()), founder).andExpect(status().isForbidden());
		asProxy(get(REPORT + "/export", program.getId()), otherCoordinator).andExpect(status().isForbidden());
	}

	@Test
	void exportsTheReportAsExcelOrCsv() throws Exception {
		checkIn(founder, 2).andExpect(status().isCreated());

		asProxy(get(REPORT + "/export", program.getId()), coordinator)
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type",
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
		String csv = new String(mockMvc.perform(get(REPORT + "/export", program.getId())
						.param("format", "CSV")
						.param("actorId", coordinator.getId().toString())
						.with(httpBasic("test", "test")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
		assertThat(csv).contains("Ad Soyad,E-posta,Takım,Hafta 1 (", "Hafta 2 (", "Katıldığı hafta");
		assertThat(csv).contains("Gülse Buran,").contains("Hayır,Evet,1");
		assertThat(csv).contains("Haftalık katılım oranı,,,%0,%50,");
	}

	// Archiving sets COMPLETED: no more check-ins, but the report and its file keep the weeks.
	@Test
	void anArchivedProgramKeepsItsWeeksInTheReport() throws Exception {
		checkIn(founder, 2).andExpect(status().isCreated());
		program.setStatus(ProgramStatus.COMPLETED);
		programRepository.saveAndFlush(program);

		asProxy(get(MINE), founder).andExpect(jsonPath("$.state").value("UNSCHEDULED"));
		checkIn(teammate, 2).andExpect(status().isConflict());
		asProxy(get(REPORT, program.getId()), coordinator)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.weeks.length()").value(2))
				.andExpect(jsonPath("$.weeks[1].checkedIn").value(1));
	}

	@Test
	void anUpcomingProgramHasAnEmptyReportNotAnError() throws Exception {
		Program later = program("Gelecek Program", admin, today.plusDays(5), today.plusDays(60));

		asProxy(get(REPORT, later.getId()), admin)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.weeks.length()").value(0))
				.andExpect(jsonPath("$.participants.length()").value(0));
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

		checkIn(student, 2).andExpect(status().isCreated());
		asProxy(get(REPORT, program.getId()), coordinator)
				.andExpect(jsonPath("$.participants[?(@.name == 'Ayşe Yılmaz')].attended[1]").value(true))
				.andExpect(jsonPath("$.participants[?(@.name == 'Ayşe Yılmaz')].teamName").value(
						org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
				.andExpect(jsonPath("$.participants[?(@.name == 'Takımsız Kişi')]").isEmpty());
	}

	// Someone who checked in and then moved to another program's team keeps that week, without the old team,
	// and does not lower the rate of the weeks after they left.
	@Test
	void aParticipantWhoLeftKeepsTheirWeekWithoutLoweringTheRest() throws Exception {
		Program third = program("Üçüncü Program", admin, today.minusDays(30), today.plusDays(30));
		teammate.setOrganization(team("Yeni Takım", third));
		userRepository.saveAndFlush(teammate);
		checkIn(founder, 2).andExpect(status().isCreated());
		ProgramAttendanceRepository rows = attendanceRepository;
		var earlier = new com.dps.teknokent.entity.ProgramAttendance();
		earlier.setId(UUID.randomUUID());
		earlier.setProgramId(program.getId());
		earlier.setUserId(teammate.getId());
		earlier.setWeekNumber(1);
		earlier.setCheckedInAt(Instant.now());
		rows.saveAndFlush(earlier);

		asProxy(get(REPORT, program.getId()), coordinator)
				.andExpect(jsonPath("$.weeks[0].participants").value(2))
				.andExpect(jsonPath("$.weeks[0].rate").value(50))
				.andExpect(jsonPath("$.weeks[1].participants").value(1))
				.andExpect(jsonPath("$.weeks[1].rate").value(100))
				.andExpect(jsonPath("$.participants[1].name").value("Mert Kaya"))
				.andExpect(jsonPath("$.participants[1].teamName").doesNotExist());
	}

	@Test
	void aNameThatLooksLikeAFormulaStaysTextInTheCsv() throws Exception {
		founder.setFullName("=HYPERLINK(\"x\")");
		userRepository.saveAndFlush(founder);

		String csv = new String(mockMvc.perform(get(REPORT + "/export", program.getId())
						.param("format", "CSV")
						.param("actorId", coordinator.getId().toString())
						.with(httpBasic("test", "test")))
				.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
		assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"");
	}

	private ResultActions checkIn(AppUser actor, int week) throws Exception {
		return mockMvc.perform(post(MINE)
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + actor.getId() + "\",\"week\":" + week + "}"));
	}

	private ResultActions asProxy(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, AppUser actor)
			throws Exception {
		return mockMvc.perform(request.param("actorId", actor.getId().toString()).with(httpBasic("test", "test")));
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
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "attendance-test-uid"), null, List.of());
	}
}
