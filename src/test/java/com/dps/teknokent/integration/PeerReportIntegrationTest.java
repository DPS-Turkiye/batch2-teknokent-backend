package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
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
import com.dps.teknokent.entity.PeerWeeklyReport;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.PeerWeeklyReportRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.PeerReportCalendar;
import com.jayway.jsonpath.JsonPath;

/**
 * Peer-mentor reports (Görüşme Raporu, Günlük Rapor, Haftalık Özet). The clock stands on Thursday 12 Nov 2026,
 * 15:00 in Istanbul, the week of Monday 9 Nov. Only the meeting's peer mentor reaches a report; anyone else, the
 * mentee included, is refused or gets not found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.edition=ODTU")
class PeerReportIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
	private static final Instant FRIDAY_EVENING = Instant.parse("2026-11-13T16:00:00Z");
	private static final String WEEK = "2026-11-09";
	private static final String TODAY = "2026-11-12";

	private static final String MEETINGS = "/api/peer-mentoring/reports/meetings";
	private static final String MEETING = "/api/peer-mentoring/reports/meetings/{meetingId}";
	private static final String MEETING_SUBMIT = "/api/peer-mentoring/reports/meetings/{meetingId}/submit";
	private static final String ACTION = "/api/peer-mentoring/reports/meetings/{meetingId}/actions/{actionId}";
	private static final String DAYS = "/api/peer-mentoring/reports/days";
	private static final String DAY = "/api/peer-mentoring/reports/days/{date}";
	private static final String DAY_SUBMIT = "/api/peer-mentoring/reports/days/{date}/submit";
	private static final String ACTIVITIES = "/api/peer-mentoring/reports/activities";
	private static final String ACTIVITY = "/api/peer-mentoring/reports/activities/{id}";
	private static final String WEEKLY = "/api/peer-mentoring/reports/weeks/{weekStart}";
	private static final String WEEKLY_SUBMIT = "/api/peer-mentoring/reports/weeks/{weekStart}/submit";

	private static final String FULL_REPORT = """
			{"meetingType":"FIRST","meetingFormat":"IN_PERSON","actualDurationMinutes":35,
			 "topics":["INTERNSHIP_APPLICATION","CV_PREPARATION"],
			 "menteeNeed":"İlk staj başvurusu için yol haritası.",
			 "discussion":"Hedef şirket listesi çıkardık.","approach":"Açık uçlu sorularla öncelikleri netleştirdik.",
			 "skills":["ACTIVE_LISTENING","GOAL_SETTING"],"skillExample":"Sektör tercihini kendisi daralttı.",
			 "outcome":"6 şirketlik hedef listesi oluşturuldu.",
			 "actions":[{"text":"CV'nin proje bölümünü yeniden yaz","owner":"MENTEE","dueDate":"2026-11-15","done":false},
			            {"text":"Staj ilanı kaynaklarını paylaş","owner":"MENTOR","dueDate":"2026-11-13","done":true}],
			 "followUpNeeded":true,"followUpAt":"2026-11-19T11:00:00Z",
			 "productivity":4,"nextTime":"Süreyi daha iyi planlamalıyım.","coordinatorSupportNeeded":false}
			""";

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
	private OrganizationRepository organizationRepository;
	@Autowired
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Autowired
	private MeetingRepository meetingRepository;
	@Autowired
	private PeerWeeklyReportRepository weeklyRepository;
	@MockitoSpyBean
	private PeerReportCalendar calendar;

	private AppUser admin;
	private AppUser coordinator;
	private AppUser mentor;
	private AppUser otherMentor;
	private AppUser mentee;
	private AppUser otherMentee;
	private Meeting held;
	private Meeting othersMeeting;
	private Meeting upcoming;
	private Meeting cancelled;
	private Meeting startupMeeting;

	@BeforeEach
	void setUp() {
		doReturn(NOW).when(calendar).now();
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Program Koordinatörü", UserRole.COORDINATOR, null);
		Program program = peerProgram("Akran Mentörlüğü 2026");
		mentor = peerMentor("Mert Demir", program);
		otherMentor = peerMentor("Kemal Arslan", program);
		mentee = student("Ayşe Yıldız", program);
		otherMentee = student("Ece Yılmaz", program);

		MentorshipRequest request = request(mentee, mentor, program, "CAREER_INTERNSHIP");
		held = meeting(request, Instant.parse("2026-11-12T08:00:00Z"), MeetingStatus.COMPLETED);
		upcoming = meeting(request, Instant.parse("2026-11-13T08:00:00Z"), MeetingStatus.SCHEDULED);
		cancelled = meeting(request, Instant.parse("2026-11-11T08:00:00Z"), MeetingStatus.CANCELLED);
		othersMeeting = meeting(request(otherMentee, otherMentor, program, "ACADEMIC_DEVELOPMENT"),
				Instant.parse("2026-11-12T09:00:00Z"), MeetingStatus.COMPLETED);
		startupMeeting = meeting(request(otherMentee, mentor, program, "Ürün"),
				Instant.parse("2026-11-12T07:00:00Z"), MeetingStatus.COMPLETED);
	}

	@Test
	void theCalendarFillsANewReportWithoutSavingIt() throws Exception {
		mockMvc.perform(get(MEETING, held.getId()).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.status").doesNotExist())
				.andExpect(jsonPath("$.editable").value(true))
				.andExpect(jsonPath("$.mentee.fullName").value("Ayşe Yıldız"))
				.andExpect(jsonPath("$.meetingType").value("FIRST"))
				.andExpect(jsonPath("$.meetingFormat").value("IN_PERSON"))
				.andExpect(jsonPath("$.actualDurationMinutes").value(30))
				.andExpect(jsonPath("$.context.meetingNumber").value(1));

		mockMvc.perform(get(MEETINGS).param("from", WEEK).param("to", TODAY).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].meetingId").value(held.getId().toString()))
				.andExpect(jsonPath("$[0].reportStatus").doesNotExist());
	}

	@Test
	void theMentorSavesSendsAndThenOnlyTicksActions() throws Exception {
		String saved = saveReport(mentor, held, "{\"topics\":[\"CV_PREPARATION\"],\"followUpNeeded\":false}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(saved, "$.id");

		mockMvc.perform(post(MEETING_SUBMIT, held.getId()).with(as(mentor)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PEER_REPORT_INCOMPLETE"));

		String full = saveReport(mentor, held, FULL_REPORT)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.actions.length()").value(2))
				.andExpect(jsonPath("$.actions[1].done").value(true))
				.andReturn().getResponse().getContentAsString();
		mockMvc.perform(post(MEETING_SUBMIT, held.getId()).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("IN_REVIEW"))
				.andExpect(jsonPath("$.editable").value(false))
				.andExpect(jsonPath("$.submittedAt").exists());

		saveReport(mentor, held, FULL_REPORT).andExpect(status().isConflict());
		String actionId = JsonPath.read(full, "$.actions[0].id");
		mockMvc.perform(patch(ACTION, held.getId(), actionId).with(as(mentor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actions[0].done").value(true))
				.andExpect(jsonPath("$.status").value("IN_REVIEW"));
	}

	@Test
	void aFollowUpWithTheSameMenteeIsNumberedAndTyped() throws Exception {
		saveReport(mentor, held, FULL_REPORT).andExpect(status().isOk());
		Meeting later = meeting(held.getMentorshipRequest(), Instant.parse("2026-11-12T11:00:00Z"),
				MeetingStatus.COMPLETED);

		mockMvc.perform(get(MEETING, later.getId()).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meetingType").value("FOLLOW_UP"))
				.andExpect(jsonPath("$.context.meetingNumber").value(2))
				.andExpect(jsonPath("$.context.previousMeeting.meetingId").value(held.getId().toString()))
				.andExpect(jsonPath("$.context.previousMeeting.topics[0]").value("INTERNSHIP_APPLICATION"))
				.andExpect(jsonPath("$.context.openActions").value(1));
	}

	@Test
	void anotherMentorsMeetingAndEveryMeetingThatIsNotAReportableOneIsNotFound() throws Exception {
		for (UUID meetingId : List.of(othersMeeting.getId(), upcoming.getId(), cancelled.getId(),
				startupMeeting.getId(), UUID.randomUUID())) {
			mockMvc.perform(get(MEETING, meetingId).with(as(mentor))).andExpect(status().isNotFound());
			saveReport(mentor, meetingId, FULL_REPORT).andExpect(status().isNotFound());
			mockMvc.perform(post(MEETING_SUBMIT, meetingId).with(as(mentor))).andExpect(status().isNotFound());
		}
	}

	@Test
	void anotherMentorCannotReachASavedReportOrItsActions() throws Exception {
		String saved = saveReport(mentor, held, FULL_REPORT).andReturn().getResponse().getContentAsString();
		String actionId = JsonPath.read(saved, "$.actions[0].id");

		mockMvc.perform(get(MEETING, held.getId()).with(as(otherMentor))).andExpect(status().isNotFound());
		saveReport(otherMentor, held, FULL_REPORT).andExpect(status().isNotFound());
		mockMvc.perform(patch(ACTION, held.getId(), actionId).with(as(otherMentor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(patch(ACTION, held.getId(), UUID.randomUUID()).with(as(mentor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void theMenteeCoordinatorAndAdminCannotUseTheMentorsReportRoutes() throws Exception {
		saveReport(mentor, held, FULL_REPORT).andExpect(status().isOk());
		for (AppUser outsider : List.of(mentee, coordinator, admin)) {
			mockMvc.perform(get(MEETING, held.getId()).with(as(outsider))).andExpect(status().isForbidden());
			mockMvc.perform(get(MEETINGS).param("from", WEEK).param("to", TODAY).with(as(outsider)))
					.andExpect(status().isForbidden());
			mockMvc.perform(get(DAY, TODAY).with(as(outsider))).andExpect(status().isForbidden());
			mockMvc.perform(get(WEEKLY, WEEK).with(as(outsider))).andExpect(status().isForbidden());
		}
	}

	@Test
	void theDailyReportWaitsForTheDaysMeetingReports() throws Exception {
		mockMvc.perform(get(DAY, TODAY).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").doesNotExist())
				.andExpect(jsonPath("$.meetings.length()").value(1))
				.andExpect(jsonPath("$.missingMeetingReports").value(1))
				.andExpect(jsonPath("$.recordedMinutes").value(30))
				.andExpect(jsonPath("$.late").value(false));

		saveDay(mentor, TODAY).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
		mockMvc.perform(post(DAY_SUBMIT, TODAY).with(as(mentor))).andExpect(status().isConflict());

		saveReport(mentor, held, FULL_REPORT).andExpect(status().isOk());
		mockMvc.perform(post(MEETING_SUBMIT, held.getId()).with(as(mentor))).andExpect(status().isOk());

		mockMvc.perform(get(DAY, TODAY).with(as(mentor)))
				.andExpect(jsonPath("$.missingMeetingReports").value(0))
				.andExpect(jsonPath("$.recordedMinutes").value(35))
				.andExpect(jsonPath("$.suggestedSkills[0]").value("ACTIVE_LISTENING"));
		mockMvc.perform(post(DAY_SUBMIT, TODAY).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("IN_REVIEW"))
				.andExpect(jsonPath("$.editable").value(false));

		saveDay(mentor, TODAY).andExpect(status().isConflict());
		addActivity(mentor, TODAY, null).andExpect(status().isConflict());
	}

	@Test
	void aStudentWhoLostAMentorRoleButNeverMentoredPeersGetsNoReportRoutes() throws Exception {
		AppUser student = userRepository.findById(otherMentee.getId()).orElseThrow();
		student.grant(UserRole.MENTOR, PermissionSource.ADMIN, Instant.parse("2026-11-01T09:00:00Z"));
		student.withdraw(PermissionSource.ADMIN, Set.of(), Instant.parse("2026-11-10T09:00:00Z"));
		userRepository.saveAndFlush(student);

		mockMvc.perform(get(DAY, "2026-11-09").with(as(student))).andExpect(status().isForbidden());
		saveDay(student, "2026-11-09").andExpect(status().isForbidden());
	}

	@Test
	void aDayNeedsItsFourAnswersAndMustBeAWorkingDayThatHasBegun() throws Exception {
		mockMvc.perform(put(DAY, "2026-11-11").with(as(mentor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"didToday\":\"Sadece bu\"}"))
				.andExpect(status().isOk());
		mockMvc.perform(post(DAY_SUBMIT, "2026-11-11").with(as(mentor)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PEER_REPORT_INCOMPLETE"));

		saveDay(mentor, "2026-11-13").andExpect(status().isBadRequest());
		saveDay(mentor, "2026-11-08").andExpect(status().isBadRequest());
		mockMvc.perform(get(DAY, "2026-11-14").with(as(mentor))).andExpect(status().isBadRequest());
	}

	@Test
	void activitiesBelongToTheMentorAndNameOnlyTheirOwnMentees() throws Exception {
		String body = addActivity(mentor, "2026-11-11", mentee.getId())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.menteeName").value("Ayşe Yıldız"))
				.andExpect(jsonPath("$.durationMinutes").value(90))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");

		addActivity(mentor, "2026-11-11", otherMentee.getId()).andExpect(status().isBadRequest());
		addActivity(mentor, "2026-11-13", null).andExpect(status().isBadRequest());

		mockMvc.perform(put(ACTIVITY, id).with(as(otherMentor))
				.contentType(MediaType.APPLICATION_JSON).content(activity("2026-11-11", null)))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete(ACTIVITY, id).with(as(otherMentor))).andExpect(status().isNotFound());

		mockMvc.perform(get(DAY, "2026-11-11").with(as(mentor)))
				.andExpect(jsonPath("$.activities.length()").value(1))
				.andExpect(jsonPath("$.recordedMinutes").value(90));
		mockMvc.perform(get(DAY, "2026-11-11").with(as(otherMentor)))
				.andExpect(jsonPath("$.activities.length()").value(0));

		mockMvc.perform(delete(ACTIVITY, id).with(as(mentor))).andExpect(status().isNoContent());
	}

	@Test
	void theWeekShowsFiveDayCards() throws Exception {
		addActivity(mentor, "2026-11-10", null).andExpect(status().isCreated());

		mockMvc.perform(get(DAYS).param("week", WEEK).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.days.length()").value(5))
				.andExpect(jsonPath("$.days[1].activities").value(1))
				.andExpect(jsonPath("$.days[3].meetings").value(1))
				.andExpect(jsonPath("$.days[3].missingMeetingReports").value(1))
				.andExpect(jsonPath("$.dailySent").value(0));
		mockMvc.perform(get(DAYS).param("week", TODAY).with(as(mentor))).andExpect(status().isBadRequest());
	}

	@Test
	void theWeeklySummaryIsComputedThenOpensOnFridayEveningAndFreezesWhenSent() throws Exception {
		saveReport(mentor, held, FULL_REPORT).andExpect(status().isOk());
		mockMvc.perform(get(WEEKLY, WEEK).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.meetings").value(1))
				.andExpect(jsonPath("$.summary.firstMeetings").value(1))
				.andExpect(jsonPath("$.summary.mentees[0].fullName").value("Ayşe Yıldız"))
				.andExpect(jsonPath("$.summary.meetingReportsSent").value(0))
				.andExpect(jsonPath("$.summary.recordedMinutes").value(35))
				.andExpect(jsonPath("$.summary.skills.length()").value(2))
				.andExpect(jsonPath("$.summary.outcomes[0].actionsDone").value(1))
				.andExpect(jsonPath("$.summary.outcomes[0].actionsTotal").value(2))
				.andExpect(jsonPath("$.dailyRequired").value(1));

		saveWeek(mentor).andExpect(status().isOk());
		mockMvc.perform(post(WEEKLY_SUBMIT, WEEK).with(as(mentor))).andExpect(status().isConflict());

		// Friday's meeting was called off, so the week's only meeting is Thursday's.
		upcoming.setStatus(MeetingStatus.CANCELLED);
		meetingRepository.saveAndFlush(upcoming);
		doReturn(FRIDAY_EVENING).when(calendar).now();
		mockMvc.perform(post(WEEKLY_SUBMIT, WEEK).with(as(mentor))).andExpect(status().isConflict());
		// Only the meeting reports gate the week (Arya, 14 Nov): no daily report is needed.
		mockMvc.perform(post(MEETING_SUBMIT, held.getId()).with(as(mentor))).andExpect(status().isOk());

		// A meeting still ahead this week keeps the summary open until it is held (or called off).
		Meeting saturday = meeting(held.getMentorshipRequest(), Instant.parse("2026-11-14T09:00:00Z"),
				MeetingStatus.SCHEDULED);
		mockMvc.perform(post(WEEKLY_SUBMIT, WEEK).with(as(mentor))).andExpect(status().isConflict());
		saturday.setStatus(MeetingStatus.CANCELLED);
		meetingRepository.saveAndFlush(saturday);

		mockMvc.perform(post(WEEKLY_SUBMIT, WEEK).with(as(mentor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("IN_REVIEW"))
				.andExpect(jsonPath("$.summary.meetingReportsSent").value(1))
				.andExpect(jsonPath("$.dailyRequired").value(1))
				.andExpect(jsonPath("$.dailySent").value(0))
				.andExpect(jsonPath("$.deadline").value("2026-11-16T21:00:00Z"));
		PeerWeeklyReport stored = weeklyRepository.findAll().getFirst();
		assertThat(stored.getSummarySnapshot()).contains("\"meetingReportsSent\":1");
		saveWeek(mentor).andExpect(status().isConflict());
		// The sent week fixes its days: no new day report and no new activity in it.
		saveDay(mentor, "2026-11-11").andExpect(status().isConflict());
		addActivity(mentor, "2026-11-11", null).andExpect(status().isConflict());

		mockMvc.perform(get(WEEKLY, WEEK).with(as(otherMentor)))
				.andExpect(jsonPath("$.status").doesNotExist())
				.andExpect(jsonPath("$.summary.meetings").value(1))
				.andExpect(jsonPath("$.summary.mentees[0].fullName").value("Ece Yılmaz"));
	}

	@Test
	void aMentorWhoLostTheBadgeFinishesTheReportsOfBeforeButNothingAfter() throws Exception {
		Meeting tuesday = meeting(held.getMentorshipRequest(), Instant.parse("2026-11-10T08:00:00Z"),
				MeetingStatus.COMPLETED);
		AppUser former = userRepository.findById(mentor.getId()).orElseThrow();
		former.withdraw(PermissionSource.BADGE, Set.of(), Instant.parse("2026-11-11T12:00:00Z"));
		userRepository.saveAndFlush(former);

		saveReport(mentor, tuesday, FULL_REPORT).andExpect(status().isOk());
		mockMvc.perform(post(MEETING_SUBMIT, tuesday.getId()).with(as(mentor))).andExpect(status().isOk());
		saveDay(mentor, "2026-11-10").andExpect(status().isOk());
		addActivity(mentor, "2026-11-10", null).andExpect(status().isCreated());

		mockMvc.perform(get(MEETING, held.getId()).with(as(mentor))).andExpect(status().isNotFound());
		saveDay(mentor, TODAY).andExpect(status().isBadRequest());
		addActivity(mentor, TODAY, null).andExpect(status().isBadRequest());
		mockMvc.perform(get(MEETINGS).param("from", WEEK).param("to", TODAY).with(as(mentor)))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].meetingId").value(tuesday.getId().toString()));
	}

	@Test
	void aMentorInTwoRunningPeerProgramsIsAskedToSortItOut() throws Exception {
		Program second = peerProgram("İkinci Akran Programı");
		ProgramMentor alsoThere = new ProgramMentor();
		alsoThere.setId(UUID.randomUUID());
		alsoThere.setProgramId(second.getId());
		alsoThere.setMentorId(mentor.getId());
		alsoThere.setStatus(ProgramMentorStatus.ACCEPTED);
		alsoThere.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		alsoThere.setCreatedAt(Instant.now());
		alsoThere.setDecidedAt(Instant.now());
		programMentorRepository.saveAndFlush(alsoThere);

		saveDay(mentor, TODAY).andExpect(status().isConflict());
		addActivity(mentor, TODAY, null).andExpect(status().isConflict());

		// Reading never trips on it; only saving a new report pins (and so asks for) the program.
		MentorshipRequest withoutProgram = request(mentee, mentor, null, "CAREER_INTERNSHIP");
		Meeting unpinned = meeting(withoutProgram, Instant.parse("2026-11-12T10:00:00Z"), MeetingStatus.COMPLETED);
		mockMvc.perform(get(MEETING, unpinned.getId()).with(as(mentor))).andExpect(status().isOk());
		saveReport(mentor, unpinned, FULL_REPORT).andExpect(status().isConflict());
	}

	@Test
	void aWeekStartsOnAMondayAndDatesStayInTheProgramsYears() throws Exception {
		mockMvc.perform(get(WEEKLY, TODAY).with(as(mentor))).andExpect(status().isBadRequest());
		mockMvc.perform(get(DAY, "1999-11-12").with(as(mentor))).andExpect(status().isBadRequest());
		mockMvc.perform(get(WEEKLY, "+999999999-12-27").with(as(mentor))).andExpect(status().isBadRequest());
		mockMvc.perform(get(MEETINGS).param("from", "1999-11-09").param("to", TODAY).with(as(mentor)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aDayHoldsAtMostFiftyActivities() throws Exception {
		for (int i = 0; i < 50; i++) {
			addActivity(mentor, "2026-11-10", null).andExpect(status().isCreated());
		}
		addActivity(mentor, "2026-11-10", null).andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------- helpers

	private ResultActions saveReport(AppUser actor, Meeting meeting, String body) throws Exception {
		return saveReport(actor, meeting.getId(), body);
	}

	private ResultActions saveReport(AppUser actor, UUID meetingId, String body) throws Exception {
		return mockMvc.perform(put(MEETING, meetingId).with(as(actor))
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions saveDay(AppUser actor, String date) throws Exception {
		return mockMvc.perform(put(DAY, date).with(as(actor)).contentType(MediaType.APPLICATION_JSON).content("""
				{"didToday":"İki görüşme yaptım.","challenge":"Emre çekingendi.","skills":["EMPATHY"],
				 "learned":"Önce güven kurmak gerekiyor.","tomorrowPlan":"Staj rehberini bitirmek."}
				"""));
	}

	private ResultActions saveWeek(AppUser actor) throws Exception {
		return mockMvc.perform(put(WEEKLY, WEEK).with(as(actor)).contentType(MediaType.APPLICATION_JSON).content("""
				{"needsObserved":"CV desteği.","growth":"Açık uçlu sorular.","nextWeekPlan":"Grup görüşmesi."}
				"""));
	}

	private ResultActions addActivity(AppUser actor, String date, UUID menteeId) throws Exception {
		return mockMvc.perform(post(ACTIVITIES).with(as(actor))
				.contentType(MediaType.APPLICATION_JSON).content(activity(date, menteeId)));
	}

	private static String activity(String date, UUID menteeId) {
		return """
				{"activityDate":"%s","type":"RESOURCE_PREP","title":"Staj başvuru rehberi hazırlığı",
				 "durationMinutes":90,"menteeId":%s,"description":"Ön yazı bölümü."}
				""".formatted(date, menteeId == null ? "null" : "\"" + menteeId + "\"");
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
