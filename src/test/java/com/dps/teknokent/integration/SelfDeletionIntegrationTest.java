package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgressStatus;
import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestMessage;
import com.dps.teknokent.entity.MentorshipRequestRecommendationRun;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.OrganizationPendingMember;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramTeamInvite;
import com.dps.teknokent.entity.ProgressUpdate;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.entity.TeamMembership;
import com.dps.teknokent.entity.UserDeletionAudit;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.UserDeletionAuditRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.security.institution.DevInstitutionLoginProvider;
import com.dps.teknokent.service.ObjectStorage;
import com.dps.teknokent.service.UserDeletionService;
import com.dps.teknokent.util.MeetingSlotTimes;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SelfDeletionIntegrationTest {

	private static final String UID = "uid-of-the-person";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private UserDeletionAuditRepository auditRepository;
	@PersistenceContext
	private EntityManager entityManager;

	@MockitoBean
	private LoginProvisioner loginProvisioner;
	@MockitoBean
	private ObjectStorage objectStorage;

	@BeforeEach
	void setUp() {
		when(loginProvisioner.uidForEmail(anyString())).thenReturn(Optional.empty());
	}

	// ---------------------------------------------------------------- the account

	@Test
	void aMenteeDeletesTheirOwnAccountAndTheMentorStillReadsTheirSharedHistory() throws Exception {
		AppUser mentee = user(UserRole.MENTEE, "Deniz Kaya", UID);
		AppUser founder = user(UserRole.MENTEE, "Ayla Yurt", null);
		AppUser mentor = user(UserRole.MENTOR, "Mert Demir", null);
		Organization startup = organization("Döngü Labs", founder);
		join(founder, startup);
		join(mentee, startup);
		MentorshipRequest accepted = request(mentee, startup, mentor, MentorshipRequestStatus.ACCEPTED);
		message(accepted, mentee, "Thanks for Monday");
		StoredDocument cv = document(mentee.getId(), null, DocumentKind.CV, null);
		String email = mentee.getEmail();
		flushAndClear();

		deleteAccount(mentee, "SİL")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deletedUserId").value(mentee.getId().toString()));

		AppUser tombstone = userRepository.findById(mentee.getId()).orElseThrow();
		assertThat(tombstone.getStatus()).isEqualTo(UserAccountStatus.DELETED);
		assertThat(tombstone.getFullName()).isEqualTo(AppUser.DELETED_FULL_NAME);
		assertThat(tombstone.getEmail()).isNotEqualTo(email);
		assertThat(tombstone.getOrganization()).isNull();
		assertThat(entityManager.find(StoredDocument.class, cv.getId())).isNull();
		verify(objectStorage).delete(cv.getS3Key());
		verify(loginProvisioner).deleteLogin(UID);

		UserDeletionAudit audit = auditRepository.findByDeletedUserId(mentee.getId()).getFirst();
		assertThat(audit.getDeletedById()).isEqualTo(mentee.getId());

		// The startup goes on without them, and the mentor's record of the request still opens.
		assertThat(entityManager.find(Organization.class, startup.getId()).isArchived()).isFalse();
		mockMvc.perform(get("/api/mentorship-requests/{id}", accepted.getId()).with(httpBasic("test", "test")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requesterName").value(AppUser.DELETED_FULL_NAME))
				.andExpect(jsonPath("$.organizationName").value("Döngü Labs"));
		assertThat(messagesOf(accepted)).extracting(MentorshipRequestMessage::getBody)
				.containsExactly("Thanks for Monday");
	}

	@Test
	void aMentorDeletesTheirAccountTypingSilOnAKeyboardWithoutTheDottedCapitalI() throws Exception {
		AppUser mentor = user(UserRole.MENTOR, "Mert Demir", null);
		flushAndClear();

		deleteAccount(mentor, "sil").andExpect(status().isOk());

		assertThat(userRepository.findById(mentor.getId()).orElseThrow().getStatus())
				.isEqualTo(UserAccountStatus.DELETED);
	}

	@Test
	void coordinatorsAndAdminsCannotDeleteThemselves() throws Exception {
		for (UserRole role : new UserRole[] { UserRole.COORDINATOR, UserRole.ADMIN }) {
			AppUser staff = user(role, "Staff Person", null);
			flushAndClear();

			deleteAccount(staff, "SİL").andExpect(status().isForbidden());

			assertUntouched(staff);
		}
	}

	@Test
	void aFounderMustDeleteTheirStartupBeforeTheirAccount() throws Exception {
		AppUser founder = user(UserRole.MENTEE, "Ayla Yurt", null);
		Organization startup = organization("Döngü Labs", founder);
		join(founder, startup);
		flushAndClear();

		deleteAccount(founder, "SİL")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SELF_DELETE_FOUNDER"));
		assertUntouched(founder);

		deleteStartup(founder, "Döngü Labs").andExpect(status().isOk());
		deleteAccount(founder, "SİL").andExpect(status().isOk());
		assertThat(userRepository.findById(founder.getId()).orElseThrow().getStatus())
				.isEqualTo(UserAccountStatus.DELETED);
	}

	@Test
	void aStaleSignInOrAWrongWordDeletesNothing() throws Exception {
		AppUser mentee = user(UserRole.MENTEE, "Deniz Kaya", null);
		flushAndClear();

		send("/api/me/delete", mentee, Instant.now().minus(16, ChronoUnit.MINUTES), "{\"confirmation\":\"SİL\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
		send("/api/me/delete", mentee, null, "{\"confirmation\":\"SİL\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
		deleteAccount(mentee, "delete")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SELF_DELETE_CONFIRMATION"));

		assertUntouched(mentee);
	}

	@Test
	void theSharedServiceCredentialsCannotDeleteAnyone() throws Exception {
		AppUser mentee = user(UserRole.MENTEE, "Deniz Kaya", null);
		flushAndClear();

		mockMvc.perform(post("/api/me/delete").with(httpBasic("test", "test"))
				.param("actorId", mentee.getId().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"confirmation\":\"SİL\"}"))
				.andExpect(status().isForbidden());

		assertUntouched(mentee);
	}

	// ---------------------------------------------------------------- what the page offers

	@Test
	void thePageOffersEachPersonOnlyWhatTheyMayDelete() throws Exception {
		AppUser founder = user(UserRole.MENTEE, "Ayla Yurt", null);
		AppUser member = user(UserRole.MENTEE, "Deniz Kaya", null);
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Kişi", null);
		Organization startup = organization("Döngü Labs", founder);
		join(founder, startup);
		join(member, startup);
		flushAndClear();

		options(founder)
				.andExpect(jsonPath("$.account.allowed").value(false))
				.andExpect(jsonPath("$.account.blockedReason").value("FOUNDER"))
				.andExpect(jsonPath("$.startup.id").value(startup.getId().toString()))
				.andExpect(jsonPath("$.startup.name").value("Döngü Labs"));
		options(member)
				.andExpect(jsonPath("$.account.allowed").value(true))
				.andExpect(jsonPath("$.account.blockedReason").isEmpty())
				.andExpect(jsonPath("$.startup").isEmpty());
		options(coordinator)
				.andExpect(jsonPath("$.account.allowed").value(false))
				.andExpect(jsonPath("$.account.blockedReason").value("STAFF"))
				.andExpect(jsonPath("$.startup").isEmpty());
	}

	// ---------------------------------------------------------------- the startup

	@Test
	void theFounderDeletesTheStartupItsDataGoesItsMembersStayAndItsHistoryWithMentorsIsKept() throws Exception {
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Kişi", null);
		Program program = program(coordinator);
		AppUser founder = user(UserRole.MENTEE, "Ayla Yurt", null);
		AppUser member = user(UserRole.MENTEE, "Deniz Kaya", null);
		AppUser mentor = user(UserRole.MENTOR, "Mert Demir", null);
		Organization startup = organization("Döngü Labs", founder);
		startup.setDescription("We build circular packaging");
		startup.setWebsite("https://dongu.example");
		startup.getSupportAreas().add("Fundraising");
		startup.setInviteCode("DONGU1");
		startup.setActiveProgramId(program.getId());
		join(founder, startup);
		join(member, startup);
		place(program, startup);
		ProgramTeamInvite joined = invite(program, startup, founder.getEmail(), Instant.now());
		ProgramTeamInvite unjoined = invite(program, startup, "someone@dongu.example", null);
		OrganizationPendingMember pending = pendingMember(startup);

		MentorshipRequest open = request(founder, startup, mentor, MentorshipRequestStatus.PENDING);
		MentorshipRequest accepted = request(founder, startup, mentor, MentorshipRequestStatus.ACCEPTED);
		MentorshipRequestMessage message = message(accepted, mentor, "See you on Monday");
		MentorshipRequestRecommendationRun run = recommendationRun(accepted);
		Mentorship mentorship = mentorship(mentor, startup);
		Meeting held = meeting(startup, mentor, Instant.now().minus(3, ChronoUnit.DAYS), MeetingStatus.COMPLETED);
		Meeting planned = meeting(startup, mentor, Instant.now().plus(3, ChronoUnit.DAYS), MeetingStatus.SCHEDULED);

		StoredDocument deck = document(founder.getId(), startup.getId(), DocumentKind.PITCH_DECK, null);
		StoredDocument logo = document(founder.getId(), startup.getId(), DocumentKind.LOGO, null);
		StoredDocument memberCv = document(member.getId(), startup.getId(), DocumentKind.CV, null);
		StoredDocument meetingFile = document(founder.getId(), startup.getId(), DocumentKind.MEETING_FILE,
				held.getId());
		ActionItem ownTask = actionItem(startup, founder, null);
		ActionItem mentorTask = actionItem(startup, founder, accepted);
		ProgressUpdate update = progressUpdate(startup, founder);
		flushAndClear();

		deleteStartup(founder, "Döngü Labs")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deletedOrganizationId").value(startup.getId().toString()))
				.andExpect(jsonPath("$.cleanupPending").value(false));

		// The startup row is a tombstone that says nothing about the startup.
		Organization tombstone = entityManager.find(Organization.class, startup.getId());
		assertThat(tombstone.getName()).isEqualTo(Organization.DELETED_NAME);
		assertThat(tombstone.getDescription()).isNull();
		assertThat(tombstone.getWebsite()).isNull();
		assertThat(tombstone.getSupportAreas()).isEmpty();
		assertThat(tombstone.getInviteCode()).isNull();
		assertThat(tombstone.getFounder()).isNull();
		assertThat(tombstone.getActiveProgramId()).isNull();
		assertThat(tombstone.isArchived()).isTrue();

		// Its members stay, out of the team and in the program.
		for (AppUser person : List.of(founder, member)) {
			AppUser row = userRepository.findById(person.getId()).orElseThrow();
			assertThat(row.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
			assertThat(row.getFullName()).isEqualTo(person.getFullName());
			assertThat(row.getOrganization()).isNull();
			assertThat(row.getProgramId()).isEqualTo(program.getId());
		}
		assertThat(stays(startup)).allSatisfy(stay -> assertThat(stay.getLeftAt()).isNotNull())
				.extracting(TeamMembership::getUserId, TeamMembership::getExitReason)
				.containsExactlyInAnyOrder(
						tuple(founder.getId(), TeamExitReason.LEFT),
						tuple(member.getId(), TeamExitReason.REMOVED));

		// What it kept for itself is gone, from the database and from storage.
		assertThat(entityManager.find(StoredDocument.class, deck.getId())).isNull();
		assertThat(entityManager.find(StoredDocument.class, logo.getId())).isNull();
		verify(objectStorage).delete(deck.getS3Key());
		verify(objectStorage).delete(logo.getS3Key());
		assertThat(entityManager.find(ActionItem.class, ownTask.getId())).isNull();
		assertThat(entityManager.find(ProgressUpdate.class, update.getId())).isNull();
		assertThat(entityManager.find(OrganizationPendingMember.class, pending.getId())).isNull();
		assertThat(entityManager.find(ProgramTeamInvite.class, unjoined.getId())).isNull();
		assertThat(entityManager.find(ProgramTeamInvite.class, joined.getId()).getTeamName())
				.isEqualTo(Organization.DELETED_NAME);
		assertThat(entityManager.createQuery(
				"select count(s) from ProgramStartup s where s.organizationId = :id", Long.class)
				.setParameter("id", startup.getId())
				.getSingleResult()).isZero();

		// A member's own file and a file shared in a meeting are not the startup's to take.
		assertThat(entityManager.find(StoredDocument.class, memberCv.getId())).isNotNull();
		assertThat(entityManager.find(StoredDocument.class, meetingFile.getId())).isNotNull();
		verify(objectStorage, never()).delete(memberCv.getS3Key());

		// What is open ends; the history with the mentor stays and opens, under the deleted name.
		assertThat(entityManager.find(MentorshipRequest.class, open.getId()).getStatus())
				.isEqualTo(MentorshipRequestStatus.WITHDRAWN);
		Mentorship ended = entityManager.find(Mentorship.class, mentorship.getId());
		assertThat(ended.getStatus()).isEqualTo(MentorshipStatus.CANCELLED);
		assertThat(ended.getEndDate()).isEqualTo(LocalDate.now(MeetingSlotTimes.APP_ZONE));
		assertThat(entityManager.find(Meeting.class, planned.getId()).getStatus()).isEqualTo(MeetingStatus.CANCELLED);
		assertThat(entityManager.find(Meeting.class, held.getId()).getStatus()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(entityManager.find(MentorshipRequest.class, accepted.getId()).getStatus())
				.isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(entityManager.find(MentorshipRequestMessage.class, message.getId()).getBody())
				.isEqualTo("See you on Monday");
		assertThat(entityManager.find(ActionItem.class, mentorTask.getId())).isNotNull();
		mockMvc.perform(get("/api/mentorship-requests/{id}", accepted.getId()).with(httpBasic("test", "test")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.organizationName").value(Organization.DELETED_NAME))
				.andExpect(jsonPath("$.requesterName").value("Ayla Yurt"));

		MentorshipRequestRecommendationRun snapshot = entityManager.find(MentorshipRequestRecommendationRun.class,
				run.getId());
		assertThat(snapshot.getStartupName()).isEqualTo(Organization.DELETED_NAME);
		assertThat(snapshot.getStartupSummary()).isNull();
		assertThat(snapshot.getStartupTechnologies()).isEmpty();

		// The member, the mentor and the program's coordinator are told; the founder is not.
		assertThat(notificationsOf(member)).singleElement()
				.satisfies(notification -> assertThat(notification.getBody()).contains("Döngü Labs"));
		assertThat(notificationsOf(mentor)).hasSize(1);
		assertThat(notificationsOf(coordinator)).singleElement()
				.satisfies(notification -> assertThat(notification.getHref())
						.isEqualTo("/coordinator/programs/" + program.getId()));
		assertThat(notificationsOf(founder)).isEmpty();

		UserDeletionAudit audit = auditRepository.findByDeletedOrganizationId(startup.getId()).getFirst();
		assertThat(audit.getDeletedUserId()).isNull();
		assertThat(audit.getDeletedById()).isEqualTo(founder.getId());
		assertThat(audit.getCleanupCompletedAt()).isNotNull();
	}

	@Test
	void onlyTheFounderMayDeleteTheStartupAndOnlyWithItsExactName() throws Exception {
		AppUser founder = user(UserRole.MENTEE, "Ayla Yurt", null);
		AppUser member = user(UserRole.MENTEE, "Deniz Kaya", null);
		Organization startup = organization("Döngü Labs", founder);
		join(founder, startup);
		join(member, startup);
		flushAndClear();

		deleteStartup(member, "Döngü Labs").andExpect(status().isNotFound());
		deleteStartup(founder, "döngü labs")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SELF_DELETE_CONFIRMATION"));
		send("/api/me/startup/delete", founder, Instant.now().minus(1, ChronoUnit.HOURS),
				"{\"confirmName\":\"Döngü Labs\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

		entityManager.clear();
		Organization untouched = entityManager.find(Organization.class, startup.getId());
		assertThat(untouched.getName()).isEqualTo("Döngü Labs");
		assertThat(untouched.isArchived()).isFalse();
		assertThat(userRepository.findById(member.getId()).orElseThrow().getOrganization()).isNotNull();
		assertThat(auditRepository.findByDeletedOrganizationId(startup.getId())).isEmpty();
	}

	@Test
	void staffAndNonFoundersAreRefusedBeforeBeingAskedToSignInAgain() throws Exception {
		AppUser coordinator = user(UserRole.COORDINATOR, "Staff Person", null);
		AppUser mentorAndAdmin = user(UserRole.MENTOR, "Mentor And Admin", null);
		mentorAndAdmin.grant(UserRole.ADMIN, PermissionSource.ADMIN, Instant.now());
		userRepository.saveAndFlush(mentorAndAdmin);
		AppUser founder = user(UserRole.MENTEE, "Ayla Yurt", null);
		AppUser member = user(UserRole.MENTEE, "Deniz Kaya", null);
		Organization startup = organization("Döngü Labs", founder);
		join(founder, startup);
		join(member, startup);
		flushAndClear();
		Instant stale = Instant.now().minus(1, ChronoUnit.HOURS);

		send("/api/me/delete", coordinator, stale, "{\"confirmation\":\"SİL\"}").andExpect(status().isForbidden());
		deleteAccount(mentorAndAdmin, "SİL").andExpect(status().isForbidden());
		send("/api/me/startup/delete", member, stale, "{\"confirmName\":\"Döngü Labs\"}")
				.andExpect(status().isNotFound());

		assertUntouched(coordinator);
		assertUntouched(mentorAndAdmin);
	}

	@Test
	void aSignInStampedTooFarInTheFutureIsNotRecent() throws Exception {
		AppUser mentee = user(UserRole.MENTEE, "Deniz Kaya", null);
		flushAndClear();

		send("/api/me/delete", mentee, Instant.now().plus(5, ChronoUnit.MINUTES), "{\"confirmation\":\"SİL\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

		assertUntouched(mentee);
	}

	@Test
	void pendingAndInstitutionLinkedAccountsCannotDeleteThemselves() throws Exception {
		AppUser pending = user(UserRole.MENTEE, "Bekleyen Kişi", null);
		pending.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(pending);
		AppUser rostered = user(UserRole.MENTEE, "Deniz Kaya", null);
		InstitutionPerson person = new InstitutionPerson(DevInstitutionLoginProvider.ID, "roster-" + rostered.getId());
		person.setFullName("Deniz Kaya");
		person.setEmail(rostered.getEmail());
		person.setSource(InstitutionDataSource.IMPORT);
		person.setSyncedAt(Instant.now());
		person.setUser(rostered);
		entityManager.persist(person);
		flushAndClear();

		deleteAccount(pending, "SİL").andExpect(status().isForbidden());
		deleteAccount(rostered, "SİL")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(UserDeletionService.CODE_INSTITUTION_LINKED));

		entityManager.clear();
		assertThat(userRepository.findById(pending.getId()).orElseThrow().getStatus())
				.isEqualTo(UserAccountStatus.PENDING);
		assertUntouched(rostered);
	}

	@Test
	void aStartupNamePastedInDecomposedFormStillMatches() throws Exception {
		AppUser founder = user(UserRole.MENTEE, "Ayla Yurt", null);
		Organization startup = organization("Döngü Labs", founder);
		join(founder, startup);
		flushAndClear();

		deleteStartup(founder, Normalizer.normalize("Döngü Labs", Normalizer.Form.NFD)).andExpect(status().isOk());

		entityManager.clear();
		assertThat(entityManager.find(Organization.class, startup.getId()).getName())
				.isEqualTo(Organization.DELETED_NAME);
	}

	// ---------------------------------------------------------------- helpers

	private ResultActions deleteAccount(AppUser person, String confirmation) throws Exception {
		return send("/api/me/delete", person, Instant.now(), "{\"confirmation\":\"" + confirmation + "\"}");
	}

	private ResultActions deleteStartup(AppUser person, String name) throws Exception {
		return send("/api/me/startup/delete", person, Instant.now(), "{\"confirmName\":\"" + name + "\"}");
	}

	private ResultActions options(AppUser person) throws Exception {
		return mockMvc.perform(get("/api/me/deletion").with(authentication(signedIn(person, Instant.now()))))
				.andExpect(status().isOk());
	}

	private ResultActions send(String path, AppUser person, Instant signedInAt, String body) throws Exception {
		return mockMvc.perform(post(path).with(authentication(signedIn(person, signedInAt)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user, Instant signedInAt) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user.getId(), user.getEmail(),
				user.getRole(), Set.of(user.getRole()), user.getStatus(), "signed-in-uid", signedInAt), null,
				List.of());
	}

	private void assertUntouched(AppUser person) {
		entityManager.clear();
		AppUser row = userRepository.findById(person.getId()).orElseThrow();
		assertThat(row.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		assertThat(row.getFullName()).isEqualTo(person.getFullName());
		assertThat(row.getEmail()).isEqualTo(person.getEmail());
		assertThat(auditRepository.findByDeletedUserId(person.getId())).isEmpty();
	}

	private List<MentorshipRequestMessage> messagesOf(MentorshipRequest request) {
		return entityManager.createQuery(
				"select m from MentorshipRequestMessage m where m.request.id = :id", MentorshipRequestMessage.class)
				.setParameter("id", request.getId())
				.getResultList();
	}

	private List<TeamMembership> stays(Organization organization) {
		return entityManager.createQuery(
				"select m from TeamMembership m where m.organization.id = :id", TeamMembership.class)
				.setParameter("id", organization.getId())
				.getResultList();
	}

	private List<Notification> notificationsOf(AppUser person) {
		return entityManager.createQuery("select n from Notification n where n.recipient.id = :id",
				Notification.class)
				.setParameter("id", person.getId())
				.getResultList();
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private AppUser user(UserRole role, String fullName, String firebaseUid) {
		AppUser user = new AppUser();
		user.setFullName(fullName);
		user.setEmail(UUID.randomUUID() + "@self-deletion.test");
		user.setRole(role);
		user.setFirebaseUid(firebaseUid);
		return userRepository.saveAndFlush(user);
	}

	private Organization organization(String name, AppUser founder) {
		Organization organization = new Organization();
		organization.setName(name);
		organization.setFounder(founder);
		entityManager.persist(organization);
		return organization;
	}

	private void join(AppUser member, Organization organization) {
		member.setOrganization(organization);
		userRepository.saveAndFlush(member);
		TeamMembership membership = new TeamMembership();
		membership.setUserId(member.getId());
		membership.setOrganization(organization);
		membership.setJoinedAt(Instant.now().minus(1, ChronoUnit.DAYS));
		entityManager.persist(membership);
	}

	private Program program(AppUser coordinator) {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("Growth Circuit");
		program.setCreatedBy(coordinator.getId());
		program.setCreatedAt(Instant.now());
		entityManager.persist(program);
		return program;
	}

	private void place(Program program, Organization organization) {
		ProgramStartup place = new ProgramStartup();
		place.setId(UUID.randomUUID());
		place.setProgramId(program.getId());
		place.setOrganizationId(organization.getId());
		place.setCreatedAt(Instant.now());
		entityManager.persist(place);
	}

	private ProgramTeamInvite invite(Program program, Organization organization, String leadEmail,
			Instant joinedAt) {
		ProgramTeamInvite invite = new ProgramTeamInvite();
		invite.setId(UUID.randomUUID());
		invite.setProgramId(program.getId());
		invite.setTeamName(organization.getName());
		invite.setLeadEmail(leadEmail);
		invite.setCode(UUID.randomUUID().toString().substring(0, 8));
		invite.setOrganizationId(organization.getId());
		invite.setCreatedBy(program.getCreatedBy());
		invite.setCreatedAt(Instant.now());
		invite.setJoinedAt(joinedAt);
		entityManager.persist(invite);
		return invite;
	}

	private OrganizationPendingMember pendingMember(Organization organization) {
		OrganizationPendingMember pending = new OrganizationPendingMember();
		pending.setId(UUID.randomUUID());
		pending.setOrganizationId(organization.getId());
		pending.setFullName("Future Teammate");
		pending.setEmail("future@dongu.example");
		pending.setCreatedAt(Instant.now());
		entityManager.persist(pending);
		return pending;
	}

	private StoredDocument document(UUID userId, UUID organizationId, DocumentKind kind, UUID meetingId) {
		StoredDocument document = new StoredDocument();
		document.setId(UUID.randomUUID());
		document.setUserId(userId);
		document.setOrganizationId(organizationId);
		document.setKind(kind);
		document.setOriginalFilename("file.pdf");
		document.setContentType("application/pdf");
		document.setSizeBytes(10);
		document.setS3Key("documents/" + userId + "/" + kind.value() + "/" + document.getId() + "-file.pdf");
		document.setCreatedAt(Instant.now());
		document.setMeetingId(meetingId);
		entityManager.persist(document);
		return document;
	}

	private MentorshipRequest request(AppUser requester, Organization organization, AppUser mentor,
			MentorshipRequestStatus status) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(requester);
		request.setOrganization(organization);
		request.setMentor(mentor);
		request.setRequestedForName(organization.getName());
		request.setSupportArea("Go to market");
		request.setSummary("We need help");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(status);
		entityManager.persist(request);
		return request;
	}

	private MentorshipRequestMessage message(MentorshipRequest request, AppUser author, String body) {
		MentorshipRequestMessage message = new MentorshipRequestMessage();
		message.setRequest(request);
		message.setAuthor(author);
		message.setMessageType(MentorshipRequestMessageType.MENTOR_MESSAGE);
		message.setBody(body);
		entityManager.persist(message);
		return message;
	}

	private MentorshipRequestRecommendationRun recommendationRun(MentorshipRequest request) {
		MentorshipRequestRecommendationRun run = new MentorshipRequestRecommendationRun();
		run.setRequest(request);
		run.setMatchingMethod("deterministic");
		run.setGeneratedAt(Instant.now());
		run.setStartupName("Döngü Labs");
		run.setStartupSector("Climate");
		run.setStartupSummary("Circular packaging for cafés");
		run.getStartupTechnologies().add("IoT");
		entityManager.persist(run);
		return run;
	}

	private Meeting meeting(Organization organization, AppUser mentor, Instant at, MeetingStatus status) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(organization);
		meeting.setMentor(mentor);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(45);
		meeting.setStatus(status);
		entityManager.persist(meeting);
		return meeting;
	}

	private Mentorship mentorship(AppUser mentor, Organization organization) {
		Mentorship mentorship = new Mentorship();
		mentorship.setMentor(mentor);
		mentorship.setOrganization(organization);
		mentorship.setStatus(MentorshipStatus.ACTIVE);
		mentorship.setStartDate(LocalDate.now(MeetingSlotTimes.APP_ZONE).minusDays(10));
		mentorship.setEndDate(LocalDate.now(MeetingSlotTimes.APP_ZONE).plusDays(60));
		entityManager.persist(mentorship);
		return mentorship;
	}

	private ActionItem actionItem(Organization organization, AppUser owner, MentorshipRequest request) {
		ActionItem item = new ActionItem();
		item.setOrganization(organization);
		item.setOwner(owner);
		item.setMentorshipRequest(request);
		item.setTitle(request == null ? "Write the investor update" : "Send the mentor the deck");
		item.setStatus(ActionItemStatus.OPEN);
		entityManager.persist(item);
		return item;
	}

	private ProgressUpdate progressUpdate(Organization organization, AppUser author) {
		ProgressUpdate update = new ProgressUpdate();
		update.setOrganization(organization);
		update.setAuthor(author);
		update.setCategory("Product");
		update.setStatus(ProgressStatus.ON_TRACK);
		update.setSummary("Pilot with two cafés");
		update.setRecordedAt(LocalDate.now(MeetingSlotTimes.APP_ZONE));
		entityManager.persist(update);
		return update;
	}
}
