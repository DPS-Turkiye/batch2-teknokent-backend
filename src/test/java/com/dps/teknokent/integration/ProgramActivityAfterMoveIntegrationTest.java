package com.dps.teknokent.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

/**
 * What a team did in a program stays in that program's views after an admin moves the team to another one: the
 * mentorship that keeps running, its booked meeting and its report numbers. The new program shows none of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgramActivityAfterMoveIntegrationTest {

	private static final String MENTORSHIPS = "/api/coordinator/mentorships";
	private static final String CALENDAR = "/api/coordinator/calendar";
	private static final String REPORT = "/api/coordinator/programs/{id}/report";
	private static final String MEETINGS = "$.items[?(@.kind == 'MEETING')]";

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
	private MentorshipRepository mentorshipRepository;
	@Autowired
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Autowired
	private MeetingRepository meetingRepository;

	private final LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
	private AppUser admin;
	private AppUser oldCoordinator;
	private AppUser newCoordinator;
	private AppUser founder;
	private AppUser oldMentor;
	private AppUser newMentor;
	private Program oldProgram;
	private Program newProgram;
	private Organization team;
	private Mentorship running;
	private Meeting booked;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		oldCoordinator = user("Eski Koordinatör", UserRole.COORDINATOR, null);
		newCoordinator = user("Yeni Koordinatör", UserRole.COORDINATOR, null);
		oldMentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		newMentor = user("Kemal Arslan", UserRole.MENTOR, null);
		oldProgram = program("Ön Kuluçka 2026", oldCoordinator);
		newProgram = program("Kuluçka 2026", newCoordinator);

		Organization created = new Organization();
		created.setName("Döngü");
		team = organizationRepository.saveAndFlush(created);
		ProgramStartup membership = new ProgramStartup();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(oldProgram.getId());
		membership.setOrganizationId(team.getId());
		membership.setCreatedAt(Instant.now());
		programStartupRepository.saveAndFlush(membership);
		team.setActiveProgramId(oldProgram.getId());
		founder = user("Gülse Buran", UserRole.MENTEE, team);
		team.setFounder(founder);
		team = organizationRepository.saveAndFlush(team);

		running = mentorship(oldMentor, oldProgram);
		booked = meeting(oldMentor, Instant.now().plus(Duration.ofDays(2)), MeetingStatus.SCHEDULED);
	}

	@Test
	void theOldProgramKeepsTheRunningMentorshipAndItsBookedMeeting() throws Exception {
		moveTheTeam();

		as(oldCoordinator, get(MENTORSHIPS).param("programId", oldProgram.getId().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(running.getId().toString()))
				.andExpect(jsonPath("$[0].programId").value(oldProgram.getId().toString()))
				.andExpect(jsonPath("$[0].organizationName").value("Döngü"));
		calendar(oldCoordinator, oldProgram)
				.andExpect(jsonPath(MEETINGS + ".id").value(booked.getId().toString()))
				.andExpect(jsonPath(MEETINGS + ".programId").value(oldProgram.getId().toString()))
				.andExpect(jsonPath(MEETINGS + ".mentorshipId").value(running.getId().toString()));
	}

	@Test
	void theNewProgramDoesNotTakeOverWhatHappenedInTheOldOne() throws Exception {
		moveTheTeam();

		as(newCoordinator, get(MENTORSHIPS).param("programId", newProgram.getId().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		calendar(newCoordinator, newProgram).andExpect(jsonPath(MEETINGS).isEmpty());
	}

	@Test
	void whatTheTeamStartsAfterTheMoveCountsInTheNewProgramOnly() throws Exception {
		moveTheTeam();
		Mentorship fresh = mentorship(newMentor, newProgram);
		Meeting next = meeting(newMentor, Instant.now().plus(Duration.ofDays(3)), MeetingStatus.SCHEDULED);

		as(newCoordinator, get(MENTORSHIPS).param("programId", newProgram.getId().toString()))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(fresh.getId().toString()));
		calendar(newCoordinator, newProgram).andExpect(jsonPath(MEETINGS + ".id").value(next.getId().toString()));
		as(oldCoordinator, get(MENTORSHIPS).param("programId", oldProgram.getId().toString()))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(running.getId().toString()));
		calendar(oldCoordinator, oldProgram).andExpect(jsonPath(MEETINGS + ".id").value(booked.getId().toString()));
	}

	@Test
	void theOldProgramsReportKeepsTheMovedTeamsMentorshipAndMeetings() throws Exception {
		meeting(oldMentor, Instant.now().minus(Duration.ofDays(2)), MeetingStatus.COMPLETED);
		moveTheTeam();
		mentorship(newMentor, newProgram);
		meeting(newMentor, Instant.now().minus(Duration.ofHours(1)), MeetingStatus.COMPLETED);

		as(oldCoordinator, get(REPORT, oldProgram.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mentorships.length()").value(1))
				.andExpect(jsonPath("$.mentorships[0].id").value(running.getId().toString()))
				.andExpect(jsonPath("$.meetings.length()").value(2))
				.andExpect(jsonPath("$.meetings[?(@.mentorId != '" + oldMentor.getId() + "')]").isEmpty())
				.andExpect(jsonPath("$.meetingsLast8Weeks").value(1));
		as(newCoordinator, get(REPORT, newProgram.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mentorships.length()").value(1))
				.andExpect(jsonPath("$.meetings.length()").value(1))
				.andExpect(jsonPath("$.meetings[0].mentorId").value(newMentor.getId().toString()));
	}

	@Test
	void theOldProgramsCardAndTheMentorshipsOwnPageAgreeWithItsList() throws Exception {
		meeting(oldMentor, Instant.now().minus(Duration.ofDays(2)), MeetingStatus.COMPLETED);
		moveTheTeam();
		mentorship(newMentor, newProgram);
		meeting(newMentor, Instant.now().minus(Duration.ofHours(1)), MeetingStatus.COMPLETED);

		as(oldCoordinator, get("/api/coordinator/programs/{id}", oldProgram.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.program.activeMentorshipCount").value(1))
				.andExpect(jsonPath("$.health.healthy").value(1))
				.andExpect(jsonPath("$.teams.mentored").value(0))
				.andExpect(jsonPath("$.missingNoteMeetings").value(1));
		as(oldCoordinator, get(MENTORSHIPS + "/{id}", running.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pastMeetings").value(1))
				.andExpect(jsonPath("$.meetings.length()").value(2));
		as(newCoordinator, get("/api/coordinator/programs/{id}", newProgram.getId()))
				.andExpect(jsonPath("$.program.activeMentorshipCount").value(1))
				.andExpect(jsonPath("$.missingNoteMeetings").value(1));
		// The new program's coordinator does not manage the mentorship that stayed in the old one.
		as(newCoordinator, get(MENTORSHIPS + "/{id}", running.getId())).andExpect(status().isBadRequest());
	}

	// A student's hidden team of one never joins a program as a startup, so it is not a team that left one.
	@Test
	void aPersonalTeamIsNotListedAsATeamThatLeft() throws Exception {
		Organization personal = new Organization();
		personal.setName("Kişisel");
		personal.setPersonal(true);
		personal = organizationRepository.saveAndFlush(personal);
		Mentorship ofAStudent = new Mentorship();
		ofAStudent.setMentor(newMentor);
		ofAStudent.setOrganization(personal);
		ofAStudent.setProgram(oldProgram);
		ofAStudent.setStatus(MentorshipStatus.ACTIVE);
		ofAStudent.setStartDate(today.minusDays(1));
		mentorshipRepository.saveAndFlush(ofAStudent);

		as(oldCoordinator, get(MENTORSHIPS).param("programId", oldProgram.getId().toString()))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(running.getId().toString()));
		as(oldCoordinator, get(REPORT, oldProgram.getId()))
				.andExpect(jsonPath("$.mentorships.length()").value(1));
	}

	// Before any move nothing changes: the team's program shows its mentorship and meeting.
	@Test
	void aTeamThatNeverMovedIsShownAsBefore() throws Exception {
		as(oldCoordinator, get(MENTORSHIPS).param("programId", oldProgram.getId().toString()))
				.andExpect(jsonPath("$.length()").value(1));
		calendar(oldCoordinator, oldProgram).andExpect(jsonPath(MEETINGS + ".id").value(booked.getId().toString()));
	}

	private void moveTheTeam() throws Exception {
		as(admin, post("/api/admin/programs/{programId}/startups/{organizationId}/move",
						newProgram.getId(), team.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromProgramId\":\"" + oldProgram.getId() + "\"}"))
				.andExpect(status().isOk());
	}

	private ResultActions calendar(AppUser actor, Program program) throws Exception {
		return as(actor, get(CALENDAR)
				.param("from", today.toString())
				.param("to", today.plusDays(7).toString())
				.param("programId", program.getId().toString()))
				.andExpect(status().isOk());
	}

	private ResultActions as(
			AppUser actor, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
			throws Exception {
		return mockMvc.perform(request.param("actorId", actor.getId().toString()).with(httpBasic("test", "test")));
	}

	private Mentorship mentorship(AppUser mentor, Program program) {
		Mentorship mentorship = new Mentorship();
		mentorship.setMentor(mentor);
		mentorship.setOrganization(team);
		mentorship.setProgram(program);
		mentorship.setStatus(MentorshipStatus.ACTIVE);
		mentorship.setStartDate(today.minusDays(1));
		return mentorshipRepository.saveAndFlush(mentorship);
	}

	/** A follow-up meeting: its own request names no program, as the calendar creates them. */
	private Meeting meeting(AppUser mentor, Instant at, MeetingStatus status) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(founder);
		request.setOrganization(team);
		request.setMentor(mentor);
		request.setRequestedForName("Döngü");
		request.setSupportArea("Finans");
		request.setSummary("Finans");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		Meeting meeting = new Meeting();
		meeting.setOrganization(team);
		meeting.setMentor(mentor);
		meeting.setMentorshipRequest(mentorshipRequestRepository.saveAndFlush(request));
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(60);
		meeting.setStatus(status);
		return meetingRepository.saveAndFlush(meeting);
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@activity.test");
		user.setRole(role);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}

	private Program program(String name, AppUser coordinator) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(admin.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		created.setStartDate(today.minusDays(30));
		created.setEndDate(today.plusDays(60));
		Program saved = programRepository.saveAndFlush(created);
		ProgramCoordinator link = new ProgramCoordinator();
		link.setId(UUID.randomUUID());
		link.setProgramId(saved.getId());
		link.setUserId(coordinator.getId());
		link.setAddedBy(admin.getId());
		link.setCreatedAt(Instant.now());
		programCoordinatorRepository.saveAndFlush(link);
		return saved;
	}
}
