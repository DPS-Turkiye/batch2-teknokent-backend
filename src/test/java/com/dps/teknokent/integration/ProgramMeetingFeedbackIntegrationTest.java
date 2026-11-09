package com.dps.teknokent.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.AppUserPrincipal;

/** Who rated whom carries names and notes: only the program's own coordinators and admins may read it. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgramMeetingFeedbackIntegrationTest {

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
	private MeetingRepository meetingRepository;
	@Autowired
	private MeetingFeedbackRepository meetingFeedbackRepository;
	@Autowired
	private MentorshipRequestRepository mentorshipRequestRepository;

	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private AppUser mentor;
	private AppUser founder;
	private Program program;
	private Meeting older;
	private Meeting newer;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Koordinatör Test", UserRole.COORDINATOR, null);
		otherCoordinator = user("Başka Koordinatör", UserRole.COORDINATOR, null);
		mentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		Organization team = organization("Döngü Test");
		founder = user("Gülse Buran", UserRole.MENTEE, team);

		program = program("Rating Test Program", admin);
		coordinatorOf(program, coordinator);
		program("Other Test Program", otherCoordinator);
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(program.getId());
		startup.setOrganizationId(team.getId());
		startup.setCreatedAt(Instant.now());
		programStartupRepository.saveAndFlush(startup);

		older = meeting(team, Instant.now().minus(Duration.ofDays(6)), "Pazar analizi");
		newer = meeting(team, Instant.now().minus(Duration.ofDays(1)), "UX Flow Review");
		feedback(older, founder, List.of(4, 4, 3, 5), List.of("Somut aksiyonlar belirlemek"));
		feedback(newer, founder, List.of(5, 5, 4, 4),
				List.of("Önceliklerimi netleştirmek", "Satış hunisini bir dahaki sefere konuşalım."));
		feedback(newer, mentor, List.of(4, 5), List.of("Hazırlıklı", "Ekip net hedeflerle geldi."));
	}

	@Test
	void theProgramsCoordinatorSeesWhoRatedWhomNewestMeetingFirst() throws Exception {
		asProxy(coordinator)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content.length()").value(3))
				.andExpect(jsonPath("$.content[0].meetingTitle").value("UX Flow Review"))
				.andExpect(jsonPath("$.content[1].meetingTitle").value("UX Flow Review"))
				.andExpect(jsonPath("$.content[2].meetingTitle").value("Pazar analizi"))
				.andExpect(jsonPath("$.content[2].organizationName").value("Döngü Test"))
				.andExpect(jsonPath("$.content[2].mentorName").value("Leyla Aksoy"))
				.andExpect(jsonPath("$.content[2].authorName").value("Gülse Buran"))
				.andExpect(jsonPath("$.content[2].side").value("MENTEE"))
				.andExpect(jsonPath("$.content[2].ratings.length()").value(4))
				.andExpect(jsonPath("$.content[2].qualities[0]").value("Somut aksiyonlar belirlemek"))
				.andExpect(jsonPath("$.content[2].note").doesNotExist())
				.andExpect(jsonPath("$.content[2].authorId").doesNotExist())
				.andExpect(jsonPath("$.content[2].authorEmail").doesNotExist());
	}

	// A team can be in two programs: the other program's meetings, cancelled and upcoming ones stay out.
	@Test
	void listsOnlyThisProgramsHeldMeetings() throws Exception {
		Organization team = founder.getOrganization();
		Program other = program("Second Test Program", otherCoordinator);
		ProgramStartup alsoThere = new ProgramStartup();
		alsoThere.setId(UUID.randomUUID());
		alsoThere.setProgramId(other.getId());
		alsoThere.setOrganizationId(team.getId());
		alsoThere.setCreatedAt(Instant.now());
		programStartupRepository.saveAndFlush(alsoThere);
		Meeting otherProgramMeeting = meeting(team, Instant.now().minus(Duration.ofDays(2)), "Diğer program");
		otherProgramMeeting.setMentorshipRequest(request(team, other));
		meetingRepository.saveAndFlush(otherProgramMeeting);
		Meeting thisProgramMeeting = meeting(team, Instant.now().minus(Duration.ofDays(3)), "Bu program");
		thisProgramMeeting.setMentorshipRequest(request(team, program));
		meetingRepository.saveAndFlush(thisProgramMeeting);
		Meeting cancelled = meeting(team, Instant.now().minus(Duration.ofDays(4)), "İptal");
		cancelled.setStatus(MeetingStatus.CANCELLED);
		meetingRepository.saveAndFlush(cancelled);
		Meeting upcoming = meeting(team, Instant.now().plus(Duration.ofDays(4)), "Gelecek");
		for (Meeting meeting : List.of(otherProgramMeeting, thisProgramMeeting, cancelled, upcoming)) {
			feedback(meeting, founder, List.of(3, 3, 3, 3), List.of());
		}

		asProxy(coordinator)
				.andExpect(jsonPath("$.totalElements").value(4))
				.andExpect(jsonPath("$.content[?(@.meetingTitle == 'Bu program')]").isNotEmpty())
				.andExpect(jsonPath("$.content[?(@.meetingTitle == 'Diğer program')]").isEmpty())
				.andExpect(jsonPath("$.content[?(@.meetingTitle == 'İptal')]").isEmpty())
				.andExpect(jsonPath("$.content[?(@.meetingTitle == 'Gelecek')]").isEmpty());
		mockMvc.perform(get("/api/coordinator/programs/{id}/meeting-feedback", other.getId())
						.param("actorId", otherCoordinator.getId().toString())
						.with(httpBasic("test", "test")))
				.andExpect(jsonPath("$.content[?(@.meetingTitle == 'Diğer program')]").isNotEmpty())
				.andExpect(jsonPath("$.content[?(@.meetingTitle == 'Bu program')]").isEmpty());
	}

	@Test
	void eachSideKeepsItsOwnRatingsQualitiesAndNote() throws Exception {
		asProxy(coordinator)
				.andExpect(jsonPath("$.content[?(@.side == 'MENTOR')].authorName").value("Leyla Aksoy"))
				.andExpect(jsonPath("$.content[?(@.side == 'MENTOR')].note").value("Ekip net hedeflerle geldi."))
				.andExpect(jsonPath("$.content[?(@.side == 'MENTOR')].qualities[0]").value("Hazırlıklı"))
				.andExpect(jsonPath("$.content[?(@.side == 'MENTEE' && @.meetingTitle == 'UX Flow Review')].note")
						.value("Satış hunisini bir dahaki sefere konuşalım."));
	}

	@Test
	void pagesThroughTheFeedback() throws Exception {
		mockMvc.perform(get("/api/coordinator/programs/{id}/meeting-feedback", program.getId())
						.param("actorId", coordinator.getId().toString())
						.param("page", "1")
						.param("size", "2")
						.with(httpBasic("test", "test")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].meetingTitle").value("Pazar analizi"));
	}

	@Test
	void anAdminReadsAnyProgram() throws Exception {
		asProxy(admin).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
	}

	@Test
	void aCoordinatorOfAnotherProgramIsRefused() throws Exception {
		asProxy(otherCoordinator).andExpect(status().isForbidden());
	}

	@Test
	void theMeetingsOwnMentorAndMenteeAreRefused() throws Exception {
		asProxy(mentor).andExpect(status().isForbidden());
		asProxy(founder).andExpect(status().isForbidden());
	}

	// A signed-in token is always the caller itself, whatever actorId it sends.
	@Test
	void aSignedInMenteeCannotBorrowTheCoordinatorsId() throws Exception {
		mockMvc.perform(get("/api/coordinator/programs/{id}/meeting-feedback", program.getId())
						.param("actorId", coordinator.getId().toString())
						.with(authentication(signedIn(founder))))
				.andExpect(status().isForbidden());
	}

	@Test
	void anUnknownProgramIsNotFoundAndNoSignInIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/coordinator/programs/{id}/meeting-feedback", UUID.randomUUID())
						.param("actorId", coordinator.getId().toString())
						.with(httpBasic("test", "test")))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/coordinator/programs/{id}/meeting-feedback", program.getId())
						.param("actorId", coordinator.getId().toString()))
				.andExpect(status().isUnauthorized());
	}

	private ResultActions asProxy(AppUser actor) throws Exception {
		return mockMvc.perform(get("/api/coordinator/programs/{id}/meeting-feedback", program.getId())
				.param("actorId", actor.getId().toString())
				.with(httpBasic("test", "test")));
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@rating.test");
		user.setRole(role);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}

	private Organization organization(String name) {
		Organization organization = new Organization();
		organization.setName(name);
		return organizationRepository.saveAndFlush(organization);
	}

	private Program program(String name, AppUser createdBy) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(createdBy.getId());
		created.setCreatedAt(Instant.now());
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

	private Meeting meeting(Organization team, Instant at, String agenda) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(team);
		meeting.setMentor(mentor);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(45);
		meeting.setStatus(MeetingStatus.COMPLETED);
		meeting.setAgenda(agenda);
		return meetingRepository.saveAndFlush(meeting);
	}

	private void feedback(Meeting meeting, AppUser author, List<Integer> ratings, List<String> benefits) {
		MeetingFeedback feedback = new MeetingFeedback();
		feedback.setMeeting(meeting);
		feedback.setAuthor(author);
		feedback.setCreatedAt(Instant.now());
		feedback.getRatings().addAll(ratings);
		feedback.getBenefits().addAll(benefits);
		meetingFeedbackRepository.saveAndFlush(feedback);
	}

	private MentorshipRequest request(Organization team, Program madeIn) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(founder);
		request.setMentor(mentor);
		request.setOrganization(team);
		request.setProgram(madeIn);
		request.setRequestedForName("Döngü Test");
		request.setSupportArea("Ürün");
		request.setSummary("Test talebi");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		return mentorshipRequestRepository.saveAndFlush(request);
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "rating-test-uid"), null, List.of());
	}
}
