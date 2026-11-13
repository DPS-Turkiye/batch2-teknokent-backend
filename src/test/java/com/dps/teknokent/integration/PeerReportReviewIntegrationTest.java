package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.PeerMeetingFormat;
import com.dps.teknokent.domain.PeerMeetingTopic;
import com.dps.teknokent.domain.PeerMeetingType;
import com.dps.teknokent.domain.PeerMentorSkill;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.PeerDailyReport;
import com.dps.teknokent.entity.PeerMeetingReport;
import com.dps.teknokent.entity.PeerWeeklyReport;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.PeerDailyReportRepository;
import com.dps.teknokent.repository.PeerMeetingReportRepository;
import com.dps.teknokent.repository.PeerWeeklyReportRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.PeerReportCalendar;

/**
 * Coordinator review of peer-mentor reports: the program's coordinators (and admins) list, read, approve and send back
 * the sent reports of their own peer programs. Drafts never show; another program's report is not found; mentors and
 * mentees are not reviewers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.edition=ODTU")
class PeerReportReviewIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
	private static final String QUEUE = "/api/peer-mentoring/reports/review";
	private static final String MEETING = "/api/peer-mentoring/reports/review/meetings/{id}";
	private static final String DAY = "/api/peer-mentoring/reports/review/days/{id}";
	private static final String WEEK = "/api/peer-mentoring/reports/review/weeks/{id}";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private InstitutionPersonRepository institutionPersonRepository;
	@Autowired
	private ProgramRepository programRepository;
	@Autowired
	private ProgramMentorRepository programMentorRepository;
	@Autowired
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Autowired
	private OrganizationRepository organizationRepository;
	@Autowired
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Autowired
	private MeetingRepository meetingRepository;
	@Autowired
	private PeerMeetingReportRepository meetingReportRepository;
	@Autowired
	private PeerDailyReportRepository dailyReportRepository;
	@Autowired
	private PeerWeeklyReportRepository weeklyReportRepository;
	@MockitoSpyBean
	private PeerReportCalendar calendar;

	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private AppUser mentor;
	private AppUser mentee;
	private Program program;
	private Program otherProgram;
	private Meeting held;
	private PeerMeetingReport sentMeetingReport;
	private PeerDailyReport sentDay;
	private PeerWeeklyReport sentWeek;
	private PeerDailyReport draftDay;
	private PeerDailyReport otherProgramsDay;

	@BeforeEach
	void setUp() {
		doReturn(NOW).when(calendar).now();
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Selin Er", UserRole.COORDINATOR, null);
		otherCoordinator = user("Diğer Koordinatör", UserRole.COORDINATOR, null);
		program = peerProgram("Akran Mentörlüğü 2026");
		otherProgram = peerProgram("Diğer Akran Programı");
		coordinatorOf(program, coordinator);
		coordinatorOf(otherProgram, otherCoordinator);
		mentor = peerMentor("Mert Demir", program);
		AppUser otherMentor = peerMentor("Kemal Arslan", otherProgram);
		mentee = student("Ayşe Yıldız", program);

		held = meeting(request(mentee, mentor, program, "CAREER_INTERNSHIP"), Instant.parse("2026-11-12T08:00:00Z"),
				MeetingStatus.COMPLETED);
		sentMeetingReport = new PeerMeetingReport();
		sentMeetingReport.setId(UUID.randomUUID());
		sentMeetingReport.setMeetingId(held.getId());
		sentMeetingReport.setMentorId(mentor.getId());
		sentMeetingReport.setMenteeId(mentee.getId());
		sentMeetingReport.setProgramId(program.getId());
		sentMeetingReport.setMeetingType(PeerMeetingType.FIRST);
		sentMeetingReport.setMeetingFormat(PeerMeetingFormat.IN_PERSON);
		sentMeetingReport.setActualDurationMinutes(35);
		sentMeetingReport.getTopics().add(PeerMeetingTopic.CV_PREPARATION);
		sentMeetingReport.setMenteeNeed("İlk staj başvurusu.");
		sentMeetingReport.setDiscussion("Hedef listesi.");
		sentMeetingReport.setApproach("Açık uçlu sorular.");
		sentMeetingReport.getSkills().add(PeerMentorSkill.ACTIVE_LISTENING);
		sentMeetingReport.setSkillExample("Kendi daralttı.");
		sentMeetingReport.setOutcome("6 şirketlik liste.");
		sentMeetingReport.setProductivity(4);
		sentMeetingReport.setNextTime("Süreyi planlamak.");
		sentMeetingReport.setCoordinatorSupportNeeded(true);
		sentMeetingReport.setCreatedAt(NOW);
		sentMeetingReport.submit(NOW);
		meetingReportRepository.saveAndFlush(sentMeetingReport);

		sentDay = day(mentor, program, LocalDate.of(2026, 11, 12), true);
		draftDay = day(mentor, program, LocalDate.of(2026, 11, 11), false);
		otherProgramsDay = day(otherMentor, otherProgram, LocalDate.of(2026, 11, 12), true);
		sentWeek = new PeerWeeklyReport();
		sentWeek.setId(UUID.randomUUID());
		sentWeek.setMentorId(mentor.getId());
		sentWeek.setProgramId(program.getId());
		sentWeek.setWeekStart(LocalDate.of(2026, 11, 9));
		sentWeek.setNeedsObserved("CV desteği.");
		sentWeek.setGrowth("Açık uçlu sorular.");
		sentWeek.setNextWeekPlan("Grup görüşmesi.");
		sentWeek.setCreatedAt(NOW);
		sentWeek.submit(NOW);
		weeklyReportRepository.saveAndFlush(sentWeek);
	}

	@Test
	void theCoordinatorListsTheSentReportsOfTheirOwnProgramOnly() throws Exception {
		mockMvc.perform(get(QUEUE).with(as(coordinator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[?(@.kind == 'MEETING')].menteeName").value("Ayşe Yıldız"))
				.andExpect(jsonPath("$[?(@.kind == 'MEETING')].coordinatorSupportNeeded").value(true))
				.andExpect(jsonPath("$[?(@.kind == 'DAILY')].id").value(sentDay.getId().toString()))
				.andExpect(jsonPath("$[?(@.kind == 'WEEKLY')].mentorName").value("Mert Demir"));
		mockMvc.perform(get(QUEUE).param("kind", "DAILY").with(as(coordinator)))
				.andExpect(jsonPath("$.length()").value(1));
		mockMvc.perform(get(QUEUE).param("programId", otherProgram.getId().toString()).with(as(coordinator)))
				.andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get(QUEUE).param("status", "DRAFT").with(as(coordinator)))
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void anAdminListsEveryProgram() throws Exception {
		mockMvc.perform(get(QUEUE).param("kind", "DAILY").with(as(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void theCoordinatorReadsTheWholeReportIncludingTheEvaluation() throws Exception {
		mockMvc.perform(get(MEETING, sentMeetingReport.getId()).with(as(coordinator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("MEETING"))
				.andExpect(jsonPath("$.mentor.fullName").value("Mert Demir"))
				.andExpect(jsonPath("$.meeting.productivity").value(4))
				.andExpect(jsonPath("$.meeting.nextTime").value("Süreyi planlamak."))
				.andExpect(jsonPath("$.meeting.coordinatorSupportNeeded").value(true));
		mockMvc.perform(get(DAY, sentDay.getId()).with(as(coordinator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.daily.didToday").value("İki görüşme."));
		mockMvc.perform(get(WEEK, sentWeek.getId()).with(as(coordinator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.weekly.needsObserved").value("CV desteği."));
	}

	@Test
	void anotherProgramsReportADraftAndAnUnknownIdAreNotFound() throws Exception {
		mockMvc.perform(get(MEETING, sentMeetingReport.getId()).with(as(otherCoordinator)))
				.andExpect(status().isNotFound());
		mockMvc.perform(post(MEETING + "/approve", sentMeetingReport.getId()).with(as(otherCoordinator)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(DAY, otherProgramsDay.getId()).with(as(coordinator))).andExpect(status().isNotFound());
		mockMvc.perform(get(DAY, draftDay.getId()).with(as(coordinator))).andExpect(status().isNotFound());
		mockMvc.perform(post(DAY + "/approve", draftDay.getId()).with(as(coordinator)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(WEEK, UUID.randomUUID()).with(as(coordinator))).andExpect(status().isNotFound());
		// A day report id is not a meeting report id, even for its own coordinator.
		mockMvc.perform(get(MEETING, sentDay.getId()).with(as(coordinator))).andExpect(status().isNotFound());
	}

	@Test
	void theMentorAndTheMenteeAreNotReviewers() throws Exception {
		for (AppUser outsider : List.of(mentor, mentee)) {
			mockMvc.perform(get(QUEUE).with(as(outsider))).andExpect(status().isForbidden());
			mockMvc.perform(get(MEETING, sentMeetingReport.getId()).with(as(outsider)))
					.andExpect(status().isForbidden());
			mockMvc.perform(post(MEETING + "/approve", sentMeetingReport.getId()).with(as(outsider)))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void approvingOnceLocksTheDecision() throws Exception {
		mockMvc.perform(post(DAY + "/approve", sentDay.getId()).with(as(coordinator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.daily.status").value("APPROVED"));
		assertThat(dailyReportRepository.findById(sentDay.getId()).orElseThrow().getReviewedBy())
				.isEqualTo(coordinator.getId());
		mockMvc.perform(post(DAY + "/approve", sentDay.getId()).with(as(coordinator)))
				.andExpect(status().isConflict());
		mockMvc.perform(revision(DAY, sentDay.getId(), "Biraz daha detay.").with(as(coordinator)))
				.andExpect(status().isConflict());
	}

	@Test
	void aRevisionReopensTheReportForItsMentorWhoSendsItAgain() throws Exception {
		mockMvc.perform(revision(MEETING, sentMeetingReport.getId(), " ").with(as(coordinator)))
				.andExpect(status().isBadRequest());
		mockMvc.perform(revision(MEETING, sentMeetingReport.getId(), "Eylem adımlarına tarih ekle.")
				.with(as(coordinator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meeting.status").value("REVISION_REQUESTED"))
				.andExpect(jsonPath("$.meeting.revisionNote").value("Eylem adımlarına tarih ekle."));

		mockMvc.perform(get("/api/peer-mentoring/reports/meetings/{id}", held.getId()).with(as(mentor)))
				.andExpect(jsonPath("$.status").value("REVISION_REQUESTED"))
				.andExpect(jsonPath("$.editable").value(true))
				.andExpect(jsonPath("$.revisionNote").value("Eylem adımlarına tarih ekle."));
		mockMvc.perform(post("/api/peer-mentoring/reports/meetings/{id}/submit", held.getId()).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("IN_REVIEW"));
		mockMvc.perform(get(QUEUE).param("kind", "MEETING").with(as(coordinator)))
				.andExpect(jsonPath("$.length()").value(1));
	}

	// ---------------------------------------------------------------- helpers

	private static MockHttpServletRequestBuilder revision(String path, UUID id, String note) {
		return post(path + "/revision", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\":\"" + note + "\"}");
	}

	private PeerDailyReport day(AppUser peerMentor, Program in, LocalDate date, boolean sent) {
		PeerDailyReport report = new PeerDailyReport();
		report.setId(UUID.randomUUID());
		report.setMentorId(peerMentor.getId());
		report.setProgramId(in.getId());
		report.setReportDate(date);
		report.setDidToday("İki görüşme.");
		report.setChallenge("Çekingen mentee.");
		report.setLearned("Güven önce gelir.");
		report.setTomorrowPlan("Rehberi bitirmek.");
		report.setCreatedAt(NOW);
		report.setUpdatedAt(NOW);
		if (sent) {
			report.submit(NOW);
		}
		return dailyReportRepository.saveAndFlush(report);
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

	private MentorshipRequest request(AppUser requester, AppUser peerMentor, Program in, String supportArea) {
		Organization team = new Organization();
		team.setName(requester.getFullName());
		team = organizationRepository.saveAndFlush(team);
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(requester);
		request.setMentor(peerMentor);
		request.setOrganization(team);
		request.setProgram(in);
		request.setRequestedForName(requester.getFullName());
		request.setSupportArea(supportArea);
		request.setSummary("Staj başvurusu");
		request.setUrgency(MentorshipRequestUrgency.NOT_URGENT);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.IN_PERSON);
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		request.setAcceptedAt(NOW.minus(Duration.ofDays(7)));
		return mentorshipRequestRepository.saveAndFlush(request);
	}

	private Meeting meeting(MentorshipRequest request, Instant at, MeetingStatus status) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(request.getOrganization());
		meeting.setMentor(request.getMentor());
		meeting.setMentorshipRequest(request);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(30);
		meeting.setStatus(status);
		return meetingRepository.saveAndFlush(meeting);
	}

	private AppUser user(String name, UserRole role, UUID programId) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@peer-reports.test");
		user.setRole(role);
		user.setProgramId(programId);
		return userRepository.saveAndFlush(user);
	}

	/** A student who earned the Akran Mentoru badge, accepted into {@code in}. */
	private AppUser peerMentor(String name, Program in) {
		AppUser user = user(name, UserRole.MENTEE, in.getId());
		user.grant(UserRole.MENTOR, PermissionSource.BADGE, Instant.now());
		user = userRepository.saveAndFlush(user);
		onTheStudentRoster(user);
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(in.getId());
		membership.setMentorId(user.getId());
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		membership.setCreatedAt(Instant.now());
		membership.setDecidedAt(Instant.now());
		programMentorRepository.saveAndFlush(membership);
		return user;
	}

	private AppUser student(String name, Program in) {
		AppUser user = user(name, UserRole.MENTEE, in.getId());
		onTheStudentRoster(user);
		return user;
	}

	private void onTheStudentRoster(AppUser user) {
		InstitutionPerson person = new InstitutionPerson("odtu-test", "student-" + user.getId());
		person.setFullName(user.getFullName());
		person.setEmail(user.getEmail());
		person.setSource(InstitutionDataSource.IMPORT);
		person.setSyncedAt(Instant.now());
		person.setKinds(Set.of(IdentityKind.STUDENT));
		person.setUser(user);
		institutionPersonRepository.saveAndFlush(person);
	}

	private Program peerProgram(String name) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(admin.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		created.setProgramType(ProgramType.PEER_MENTORING);
		return programRepository.saveAndFlush(created);
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor as(AppUser user) {
		return authentication(new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "peer-reports-test-uid"), null,
				List.of()));
	}
}
