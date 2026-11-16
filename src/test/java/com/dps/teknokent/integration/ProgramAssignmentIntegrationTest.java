package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramAssignment;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramAssignmentRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

/** "Ödev ver": one Ödev for every team of a program, handed in on Süreç, followed in the program's Ödevler. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgramAssignmentIntegrationTest {

	private static final String ASSIGNMENTS = "/api/coordinator/programs/{programId}/assignments";
	private static final String PROCESS = "/api/organizations/{organizationId}/process";

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
	private ProgramAssignmentRepository programAssignmentRepository;
	@Autowired
	private ActionItemRepository actionItemRepository;
	@Autowired
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Autowired
	private NotificationRepository notificationRepository;
	@Autowired
	private StoredDocumentRepository storedDocumentRepository;

	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private Program program;
	private Program otherProgram;
	private Organization dongu;
	private Organization kume;
	private AppUser donguFounder;
	private AppUser kumeFounder;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Elif Demir", UserRole.COORDINATOR, null);
		otherCoordinator = user("Burcu Şen", UserRole.COORDINATOR, null);
		program = program("Kuluçka 2026");
		otherProgram = program("Ön Kuluçka 2026");
		coordinatorOf(program, coordinator);
		coordinatorOf(otherProgram, otherCoordinator);
		dongu = team("Döngü", program);
		donguFounder = founderOf(dongu, "Gülse Buran");
		kume = team("Küme", program);
		kumeFounder = founderOf(kume, "Mert Kaya");
	}

	@Test
	void givesTheOdevToEveryApprovedTeamOfTheProgramAndTellsEachFounder() throws Exception {
		Organization waiting = team("Bekleyen", program);
		waiting.setApprovalStatus(UserAccountStatus.PENDING);
		organizationRepository.saveAndFlush(waiting);
		founderOf(waiting, "Ayşe Bekler");
		Organization elsewhere = team("Başka", otherProgram);
		founderOf(elsewhere, "Can Uzak");

		create(coordinator, body("Pazar araştırması", tomorrow(), false))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.title").value("Pazar araştırması"))
				.andExpect(jsonPath("$.fileRequired").value(false))
				.andExpect(jsonPath("$.openCount").value(2))
				.andExpect(jsonPath("$.teams.length()").value(2))
				.andExpect(jsonPath("$.teams[0].organizationName").value("Döngü"))
				.andExpect(jsonPath("$.teams[0].state").value("OPEN"))
				.andExpect(jsonPath("$.teams[1].organizationName").value("Küme"));

		assertThat(copiesOf(dongu)).singleElement().satisfies(item -> {
			assertThat(item.isProcessAssignment()).isTrue();
			assertThat(item.getOwner().getId()).isEqualTo(donguFounder.getId());
			assertThat(item.getCreatedBy().getId()).isEqualTo(coordinator.getId());
			assertThat(item.getDueDate()).isEqualTo(tomorrow());
		});
		assertThat(copiesOf(kume)).hasSize(1);
		assertThat(copiesOf(waiting)).isEmpty();
		assertThat(copiesOf(elsewhere)).isEmpty();
		assertThat(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(donguFounder.getId()))
				.singleElement()
				.satisfies(notification -> {
					assertThat(notification.getBody()).contains("Pazar araştırması");
					assertThat(notification.getHref()).isEqualTo("/startup/process");
				});
	}

	@Test
	void anAdminMayGiveOneToo() throws Exception {
		create(admin, body("Pitch provası", null, true)).andExpect(status().isCreated());

		assertThat(copiesOf(dongu)).hasSize(1);
	}

	@Test
	void refusesACoordinatorOfAnotherProgramAMentorAndAMentee() throws Exception {
		AppUser mentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		acceptedMentorship(mentor, dongu);

		create(otherCoordinator, body("Pazar araştırması", null, false)).andExpect(status().is4xxClientError());
		create(mentor, body("Pazar araştırması", null, false)).andExpect(status().is4xxClientError());
		create(donguFounder, body("Pazar araştırması", null, false)).andExpect(status().is4xxClientError());
		list(otherCoordinator).andExpect(status().is4xxClientError());
		list(mentor).andExpect(status().is4xxClientError());
		list(donguFounder).andExpect(status().is4xxClientError());

		assertThat(programAssignmentRepository.findByProgramIdOrderByCreatedAtDesc(program.getId())).isEmpty();
		assertThat(copiesOf(dongu)).isEmpty();
	}

	@Test
	void refusesADueDateAlreadyBehindABlankTitleAndAnArchivedProgram() throws Exception {
		create(coordinator, body(" ", null, false)).andExpect(status().isBadRequest());
		create(coordinator, body("Geç kalmış", today().minusDays(1), false)).andExpect(status().isBadRequest());

		program.setStatus(ProgramStatus.COMPLETED);
		programRepository.saveAndFlush(program);
		create(coordinator, body("Arşivde", null, false)).andExpect(status().isBadRequest());

		assertThat(programAssignmentRepository.findByProgramIdOrderByCreatedAtDesc(program.getId())).isEmpty();
	}

	@Test
	void aTeamThatJoinsLaterGetsTheOpenOdevlerButNotThePastOnes() throws Exception {
		create(coordinator, body("Açık ödev", tomorrow(), false)).andExpect(status().isCreated());
		create(coordinator, body("Süresiz ödev", null, false)).andExpect(status().isCreated());
		ProgramAssignment past = pastAssignment("Geçmiş ödev");

		Organization late = team("Geç Gelen", program);
		AppUser lateFounder = founderOf(late, "Deniz İnanç");
		mockMvc.perform(get(PROCESS, late.getId())
				.param("actorId", lateFounder.getId().toString())
				.with(httpBasic("test", "test")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignments.length()").value(2));

		assertThat(copiesOf(late)).extracting(ActionItem::getTitle)
				.containsExactlyInAnyOrder("Açık ödev", "Süresiz ödev");
		assertThat(copiesOf(late)).noneMatch(item -> item.getProgramAssignment().getId().equals(past.getId()));
		// Reading again hands out nothing twice.
		mockMvc.perform(get(PROCESS, late.getId())
				.param("actorId", lateFounder.getId().toString())
				.with(httpBasic("test", "test")))
				.andExpect(jsonPath("$.assignments.length()").value(2));
	}

	@Test
	void theCoordinatorsListAlsoHandsTheOpenOnesToALateTeam() throws Exception {
		create(coordinator, body("Açık ödev", tomorrow(), false)).andExpect(status().isCreated());
		Organization late = team("Geç Gelen", program);
		founderOf(late, "Deniz İnanç");

		list(coordinator)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].teams.length()").value(3));

		assertThat(copiesOf(late)).hasSize(1);
	}

	@Test
	void aTeamHandsInWithoutAFileWhenNoneIsAskedAndTheListSaysSo() throws Exception {
		create(coordinator, body("Pazar araştırması", tomorrow(), false)).andExpect(status().isCreated());
		ActionItem copy = copiesOf(dongu).getFirst();

		submit(dongu, donguFounder, copy, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignments[0].fileRequired").value(false))
				.andExpect(jsonPath("$.assignments[0].programAssignmentId").exists())
				.andExpect(jsonPath("$.assignments[0].status").value("COMPLETED"));

		list(coordinator)
				.andExpect(jsonPath("$[0].submittedCount").value(1))
				.andExpect(jsonPath("$[0].openCount").value(1))
				.andExpect(jsonPath("$[0].teams[0].state").value("SUBMITTED"))
				.andExpect(jsonPath("$[0].teams[0].submittedAt").exists())
				.andExpect(jsonPath("$[0].teams[1].state").value("OPEN"));
	}

	@Test
	void aFileIsRequiredWhenTheOdevAsksForOneAndTheListLinksIt() throws Exception {
		create(coordinator, body("Finansal model", tomorrow(), true)).andExpect(status().isCreated());
		ActionItem copy = copiesOf(dongu).getFirst();

		submit(dongu, donguFounder, copy, null).andExpect(status().isBadRequest());
		assertThat(actionItemRepository.findById(copy.getId()).orElseThrow().getStatus())
				.isEqualTo(ActionItemStatus.OPEN);

		StoredDocument file = document(dongu, donguFounder);
		submit(dongu, donguFounder, copy, file.getId()).andExpect(status().isOk());

		list(coordinator)
				.andExpect(jsonPath("$[0].teams[0].state").value("SUBMITTED"))
				.andExpect(jsonPath("$[0].teams[0].documentId").value(file.getId().toString()))
				.andExpect(jsonPath("$[0].teams[0].filename").value("finansal-model.pdf"));
	}

	@Test
	void aHandInAfterTheDueDateIsLateAndAMissedOneIsOverdue() throws Exception {
		ProgramAssignment past = pastAssignment("Geçmiş ödev");
		ActionItem donguCopy = copy(past, dongu, donguFounder);
		donguCopy.setStatus(ActionItemStatus.COMPLETED);
		donguCopy.setSubmittedAt(Instant.now());
		actionItemRepository.saveAndFlush(donguCopy);
		copy(past, kume, kumeFounder);

		list(coordinator)
				.andExpect(jsonPath("$[0].lateCount").value(1))
				.andExpect(jsonPath("$[0].overdueCount").value(1))
				.andExpect(jsonPath("$[0].teams[0].state").value("LATE"))
				.andExpect(jsonPath("$[0].teams[1].state").value("OVERDUE"));
	}

	private ResultActions create(AppUser actor, String body) throws Exception {
		return mockMvc.perform(post(ASSIGNMENTS, program.getId())
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + actor.getId() + "\"," + body + "}"));
	}

	private ResultActions list(AppUser actor) throws Exception {
		return mockMvc.perform(get(ASSIGNMENTS, program.getId())
				.param("actorId", actor.getId().toString())
				.with(httpBasic("test", "test")));
	}

	private ResultActions submit(Organization team, AppUser actor, ActionItem item, UUID documentId)
			throws Exception {
		String document = documentId == null ? "" : ",\"documentId\":\"" + documentId + "\"";
		return mockMvc.perform(put(PROCESS + "/assignments/{assignmentId}/submission", team.getId(), item.getId())
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + actor.getId() + "\"" + document + "}"));
	}

	private static String body(String title, LocalDate dueDate, boolean fileRequired) {
		return "\"title\":\"" + title + "\",\"description\":\"Hedef pazarı anlat.\""
				+ (dueDate == null ? "" : ",\"dueDate\":\"" + dueDate + "\"")
				+ ",\"fileRequired\":" + fileRequired;
	}

	private List<ActionItem> copiesOf(Organization team) {
		return actionItemRepository.findByOrganizationIdOrderByCreatedAtDesc(team.getId()).stream()
				.filter(item -> item.getProgramAssignment() != null)
				.toList();
	}

	private ProgramAssignment pastAssignment(String title) {
		ProgramAssignment assignment = new ProgramAssignment();
		assignment.setId(UUID.randomUUID());
		assignment.setProgramId(program.getId());
		assignment.setTitle(title);
		assignment.setDueDate(today().minusDays(3));
		assignment.setCreatedBy(coordinator);
		assignment.setCreatedAt(Instant.now().minusSeconds(864_000));
		return programAssignmentRepository.saveAndFlush(assignment);
	}

	private ActionItem copy(ProgramAssignment assignment, Organization team, AppUser owner) {
		ActionItem item = new ActionItem();
		item.setOrganization(team);
		item.setOwner(owner);
		item.setTitle(assignment.getTitle());
		item.setStatus(ActionItemStatus.OPEN);
		item.setDueDate(assignment.getDueDate());
		item.setProcessAssignment(true);
		item.setCreatedBy(coordinator);
		item.setProgramAssignment(assignment);
		return actionItemRepository.saveAndFlush(item);
	}

	private StoredDocument document(Organization team, AppUser uploader) {
		StoredDocument document = new StoredDocument();
		document.setId(UUID.randomUUID());
		document.setCreatedAt(Instant.now());
		document.setOrganizationId(team.getId());
		document.setUserId(uploader.getId());
		document.setKind(DocumentKind.HOMEWORK);
		document.setOriginalFilename("finansal-model.pdf");
		document.setContentType("application/pdf");
		document.setSizeBytes(1024L);
		document.setS3Key("test/" + UUID.randomUUID());
		return storedDocumentRepository.saveAndFlush(document);
	}

	private void acceptedMentorship(AppUser mentor, Organization team) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(donguFounder);
		request.setMentor(mentor);
		request.setOrganization(team);
		request.setProgram(program);
		request.setRequestedForName(team.getName());
		request.setSupportArea("Pazar");
		request.setSummary("Pazar araştırması");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		mentorshipRequestRepository.saveAndFlush(request);
	}

	private AppUser founderOf(Organization team, String name) {
		AppUser founder = user(name, UserRole.MENTEE, team);
		team.setFounder(founder);
		organizationRepository.saveAndFlush(team);
		return founder;
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@odev.test");
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

	private Program program(String name) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(admin.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
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

	private static LocalDate today() {
		return LocalDate.now(MeetingSlotTimes.APP_ZONE);
	}

	private static LocalDate tomorrow() {
		return today().plusDays(1);
	}
}
