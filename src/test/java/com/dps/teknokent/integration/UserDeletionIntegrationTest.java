package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.PlatformFeedbackKind;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserDeletionCleanupKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AppUserIdentity;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestMessage;
import com.dps.teknokent.entity.MentorshipRequestRecommendationItem;
import com.dps.teknokent.entity.MentorshipRequestRecommendationRun;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.OnboardingDraft;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.PlatformFeedback;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.entity.TeamMembership;
import com.dps.teknokent.entity.UserDeletionAudit;
import com.dps.teknokent.entity.UserDeletionCleanup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.UserDeletionAuditRepository;
import com.dps.teknokent.repository.UserDeletionCleanupRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.service.AppUserService;
import com.dps.teknokent.service.NotificationService;
import com.dps.teknokent.service.ObjectStorage;
import com.dps.teknokent.service.UserDeletionCleanupService;
import com.dps.teknokent.service.UserDeletionService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserDeletionIntegrationTest {

	private static final String UID = "uid-of-the-deleted-person";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private UserDeletionAuditRepository auditRepository;
	@Autowired
	private UserDeletionCleanupRepository cleanupRepository;
	@Autowired
	private UserDeletionCleanupService cleanupService;
	@Autowired
	private AppUserService appUserService;
	@Autowired
	private NotificationService notificationService;
	@PersistenceContext
	private EntityManager entityManager;

	@MockitoBean
	private LoginProvisioner loginProvisioner;
	@MockitoBean
	private ObjectStorage objectStorage;

	private AppUser admin;

	@BeforeEach
	void setUp() {
		admin = user(UserRole.ADMIN, "Admin Person", null);
		when(loginProvisioner.uidForEmail(anyString())).thenReturn(Optional.empty());
	}

	// ---------------------------------------------------------------- what goes and what stays

	@Test
	void theAccountBecomesATombstoneWithNoPersonalDataAndItsOwnDataIsGone() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		String email = target.getEmail();
		target.setPhone("+905551112233");
		target.setBio("About me");
		target.setLinkedinUrl("https://linkedin.com/in/deniz");
		target.setJobTitle("Founder");
		target.getExpertise().add("Fintech");
		identity(target);
		draft(UID);
		notificationService.create(target, "For the person", "Only they read this", null);
		feedback(target);
		StoredDocument cv = document(target.getId(), null, DocumentKind.CV, null);
		flushAndClear();

		delete(admin, target.getId(), email)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deletedUserId").value(target.getId().toString()))
				.andExpect(jsonPath("$.cleanupPending").value(false));

		AppUser tombstone = userRepository.findById(target.getId()).orElseThrow();
		assertThat(tombstone.getStatus()).isEqualTo(UserAccountStatus.DELETED);
		assertThat(tombstone.getFullName()).isEqualTo(AppUser.DELETED_FULL_NAME);
		assertThat(tombstone.getEmail()).isEqualTo(AppUser.deletedEmailFor(target.getId())).isNotEqualTo(email);
		assertThat(tombstone.getFirebaseUid()).isNull();
		assertThat(tombstone.getPhone()).isNull();
		assertThat(tombstone.getBio()).isNull();
		assertThat(tombstone.getLinkedinUrl()).isNull();
		assertThat(tombstone.getJobTitle()).isNull();
		assertThat(tombstone.getExpertise()).isEmpty();
		assertThat(tombstone.activePermissions()).isEmpty();
		assertThat(tombstone.getSignInsRevokedAt()).isNotNull();

		assertThat(count("select count(i) from AppUserIdentity i where i.user.id = :id", target.getId())).isZero();
		assertThat(count("select count(n) from Notification n where n.recipient.id = :id", target.getId())).isZero();
		assertThat(count("select count(f) from PlatformFeedback f where f.author.id = :id", target.getId())).isZero();
		assertThat(entityManager.find(OnboardingDraft.class, UID)).isNull();
		assertThat(entityManager.find(StoredDocument.class, cv.getId())).isNull();
		verify(objectStorage).delete(cv.getS3Key());
		verify(loginProvisioner).deleteLogin(UID);
	}

	@Test
	void theAuditRowNamesOnlyIdsAndIsCompleteOnceTheCleanupIsDone() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		String email = target.getEmail();
		flushAndClear();

		delete(admin, target.getId(), email).andExpect(status().isOk());

		UserDeletionAudit audit = auditRepository.findByDeletedUserId(target.getId()).getFirst();
		assertThat(audit.getDeletedById()).isEqualTo(admin.getId());
		assertThat(audit.getDeletedAt()).isNotNull();
		assertThat(audit.getCleanupCompletedAt()).isNotNull();
		assertThat(cleanupRepository.existsByAuditId(audit.getId())).isFalse();
		// The table has no column that could hold a name, an address or a uid.
		assertThat(UserDeletionAudit.class.getDeclaredFields())
				.extracting(Field::getName)
				.containsExactlyInAnyOrder("id", "deletedUserId", "deletedById", "deletedAt", "cleanupCompletedAt");
	}

	@Test
	void sharedHistoryStaysAndShowsTheDeletedName() throws Exception {
		AppUser mentor = user(UserRole.MENTOR, "Mert Demir", UID);
		String email = mentor.getEmail();
		AppUser mentee = user(UserRole.MENTEE, "Ayla Yurt", null);
		Organization startup = organization("Shared Startup", mentee);
		join(mentee, startup);
		MentorshipRequest open = request(mentee, startup, mentor, MentorshipRequestStatus.PENDING);
		MentorshipRequest accepted = request(mentee, startup, mentor, MentorshipRequestStatus.ACCEPTED);
		MentorshipRequestMessage message = message(accepted, mentor, "See you on Monday");
		Meeting held = meeting(startup, mentor, Instant.now().minus(3, ChronoUnit.DAYS), MeetingStatus.COMPLETED);
		Meeting planned = meeting(startup, mentor, Instant.now().plus(3, ChronoUnit.DAYS), MeetingStatus.SCHEDULED);
		Mentorship mentorship = mentorship(mentor, startup);
		Notification naming = notificationService.create(mentee, "Mert Demir sent a message", "From Mert Demir", null);
		flushAndClear();

		delete(admin, mentor.getId(), email).andExpect(status().isOk());

		MentorshipRequest closed = entityManager.find(MentorshipRequest.class, open.getId());
		assertThat(closed.getStatus()).isEqualTo(MentorshipRequestStatus.DECLINED);
		assertThat(closed.getMentor().getFullName()).isEqualTo(AppUser.DELETED_FULL_NAME);
		assertThat(entityManager.find(MentorshipRequest.class, accepted.getId()).getStatus())
				.isEqualTo(MentorshipRequestStatus.ACCEPTED);

		MentorshipRequestMessage kept = entityManager.find(MentorshipRequestMessage.class, message.getId());
		assertThat(kept.getBody()).isEqualTo("See you on Monday");
		assertThat(kept.getAuthor().getFullName()).isEqualTo(AppUser.DELETED_FULL_NAME);

		assertThat(entityManager.find(Meeting.class, held.getId()).getStatus()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(entityManager.find(Meeting.class, planned.getId()).getStatus()).isEqualTo(MeetingStatus.CANCELLED);
		assertThat(entityManager.find(Mentorship.class, mentorship.getId()).getStatus())
				.isEqualTo(MentorshipStatus.CANCELLED);

		Notification rewritten = entityManager.find(Notification.class, naming.getId());
		assertThat(rewritten.getTitle()).isEqualTo(AppUser.DELETED_FULL_NAME + " sent a message");
		assertThat(rewritten.getBody()).isEqualTo("From " + AppUser.DELETED_FULL_NAME);

		// The mentee is told once, without the person's name.
		List<Notification> told = entityManager.createQuery(
				"select n from Notification n where n.recipient.id = :id and n.id <> :other", Notification.class)
				.setParameter("id", mentee.getId())
				.setParameter("other", naming.getId())
				.getResultList();
		assertThat(told).hasSize(1);
		assertThat(told.getFirst().getBody()).doesNotContain("Mert");

		// The other person's account and startup are untouched.
		AppUser untouched = userRepository.findById(mentee.getId()).orElseThrow();
		assertThat(untouched.getFullName()).isEqualTo("Ayla Yurt");
		assertThat(untouched.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		assertThat(untouched.getOrganization().getArchivedAt()).isNull();
	}

	@Test
	void aNameInSomeoneElsesNotificationIsReplacedWithoutOverflowingTheColumn() throws Exception {
		AppUser target = user(UserRole.MENTOR, "Ali B", UID);
		String email = target.getEmail();
		AppUser other = user(UserRole.MENTEE, "Other Person", null);
		Notification full = notificationService.create(other, "Ali B " + "x".repeat(154), "Ali B", null);
		flushAndClear();

		delete(admin, target.getId(), email).andExpect(status().isOk());

		Notification rewritten = entityManager.find(Notification.class, full.getId());
		assertThat(rewritten.getTitle()).hasSize(160).startsWith(AppUser.DELETED_FULL_NAME);
	}

	// Two people with one name cannot be told apart in a notification's text, so it is left as it was said.
	@Test
	void aNotificationThatCouldBeAboutANamesakeIsLeftAlone() throws Exception {
		AppUser target = user(UserRole.MENTOR, "Ahmet Yılmaz", UID);
		String email = target.getEmail();
		user(UserRole.MENTOR, "Ahmet Yılmaz", null);
		AppUser reader = user(UserRole.MENTEE, "Other Person", null);
		Notification about = notificationService.create(reader, "Ahmet Yılmaz sent a message", "From Ahmet Yılmaz", null);
		flushAndClear();

		delete(admin, target.getId(), email).andExpect(status().isOk());

		assertThat(entityManager.find(Notification.class, about.getId()).getTitle()).isEqualTo("Ahmet Yılmaz sent a message");
	}

	// "Ali Can" inside "Mehmet Ali Can" is another person's name; inside "Alican" it is no name at all.
	@Test
	void theNameIsReplacedOnlyWhereItStandsAsWholeWords() throws Exception {
		AppUser target = user(UserRole.MENTOR, "Ali Can", UID);
		String email = target.getEmail();
		AppUser reader = user(UserRole.MENTEE, "Other Person", null);
		Notification glued = notificationService.create(reader, "XAli Canx joined", "Ali Can, welcome", null);
		flushAndClear();

		delete(admin, target.getId(), email).andExpect(status().isOk());

		Notification after = entityManager.find(Notification.class, glued.getId());
		assertThat(after.getTitle()).isEqualTo("XAli Canx joined");
		assertThat(after.getBody()).isEqualTo(AppUser.DELETED_FULL_NAME + ", welcome");
	}

	@Test
	void aRecommendationKeepsItsRowButNotTheMentorsProfileCopy() throws Exception {
		AppUser mentor = user(UserRole.MENTOR, "Mert Demir", UID);
		String email = mentor.getEmail();
		AppUser mentee = user(UserRole.MENTEE, "Ayla Yurt", null);
		Organization startup = organization("Shared Startup", mentee);
		join(mentee, startup);
		MentorshipRequest request = request(mentee, startup, mentor, MentorshipRequestStatus.ACCEPTED);
		MentorshipRequestRecommendationRun run = new MentorshipRequestRecommendationRun();
		run.setRequest(request);
		run.setMatchingMethod("test");
		run.setGeneratedAt(Instant.now());
		entityManager.persist(run);
		MentorshipRequestRecommendationItem item = new MentorshipRequestRecommendationItem();
		item.setRun(run);
		item.setMentor(mentor);
		item.setRank(1);
		item.setMentorName("Mert Demir");
		item.setProfessionalTitle("CTO");
		item.setCompany("Demir Yazılım");
		item.getReasons().add("Mert has built two fintech products");
		entityManager.persist(item);
		flushAndClear();

		delete(admin, mentor.getId(), email).andExpect(status().isOk());

		MentorshipRequestRecommendationItem kept = entityManager.find(MentorshipRequestRecommendationItem.class, item.getId());
		assertThat(kept.getRank()).isEqualTo(1);
		assertThat(kept.getMentorName()).isEqualTo(AppUser.DELETED_FULL_NAME);
		assertThat(kept.getProfessionalTitle()).isEmpty();
		assertThat(kept.getCompany()).isEmpty();
		assertThat(kept.getReasons()).isEmpty();
	}

	// ---------------------------------------------------------------- startups

	// The hidden team of one behind an individual student is named after the student.
	@Test
	void aPersonalTeamDoesNotKeepTheStudentsName() throws Exception {
		AppUser student = user(UserRole.MENTEE, "Selin Arı", UID);
		String email = student.getEmail();
		Organization personal = organization("Selin Arı", student);
		personal.setPersonal(true);
		join(student, personal);
		flushAndClear();

		delete(admin, student.getId(), email).andExpect(status().isOk());

		Organization after = entityManager.find(Organization.class, personal.getId());
		assertThat(after.getName()).isEqualTo(AppUser.DELETED_FULL_NAME);
		assertThat(after.getArchivedAt()).isNotNull();
	}

	@Test
	void aTeammateWhoIsNotApprovedDoesNotInheritTheStartup() throws Exception {
		AppUser founder = user(UserRole.MENTEE, "Founder Person", UID);
		String email = founder.getEmail();
		AppUser waiting = user(UserRole.MENTEE, "Waiting Member", null);
		waiting.setStatus(UserAccountStatus.PENDING);
		Organization startup = organization("Team Startup", founder);
		join(founder, startup);
		join(waiting, startup);
		flushAndClear();

		delete(admin, founder.getId(), email).andExpect(status().isOk());

		Organization after = entityManager.find(Organization.class, startup.getId());
		assertThat(after.getFounder()).isNull();
		assertThat(after.getArchivedAt()).isNull();
	}

	@Test
	void aFounderWithTeammatesHandsTheStartupToTheLongestStandingMember() throws Exception {
		AppUser founder = user(UserRole.MENTEE, "Founder Person", UID);
		String email = founder.getEmail();
		AppUser newer = user(UserRole.MENTEE, "Newer Member", null);
		AppUser older = user(UserRole.MENTEE, "Older Member", null);
		Organization startup = organization("Team Startup", founder);
		join(founder, startup, Instant.now().minus(30, ChronoUnit.DAYS));
		join(newer, startup, Instant.now().minus(2, ChronoUnit.DAYS));
		join(older, startup, Instant.now().minus(20, ChronoUnit.DAYS));
		StoredDocument deck = document(founder.getId(), startup.getId(), DocumentKind.PITCH_DECK, null);
		StoredDocument cv = document(founder.getId(), startup.getId(), DocumentKind.CV, null);
		flushAndClear();

		delete(admin, founder.getId(), email).andExpect(status().isOk());

		Organization after = entityManager.find(Organization.class, startup.getId());
		assertThat(after.getFounder().getId()).isEqualTo(older.getId());
		assertThat(after.getArchivedAt()).isNull();
		assertThat(userRepository.findById(founder.getId()).orElseThrow().getOrganization()).isNull();
		assertThat(count("select count(m) from TeamMembership m where m.userId = :id and m.leftAt is null",
				founder.getId())).isZero();
		// The team keeps its pitch deck; the person's CV goes even though it hung on the startup.
		assertThat(entityManager.find(StoredDocument.class, deck.getId())).isNotNull();
		assertThat(entityManager.find(StoredDocument.class, cv.getId())).isNull();
		verify(objectStorage, never()).delete(deck.getS3Key());
	}

	@Test
	void aStartupLeftWithNobodyIsArchivedAndItsFilesGoWhileTheMentorsHistoryStays() throws Exception {
		AppUser founder = user(UserRole.MENTEE, "Solo Founder", UID);
		String email = founder.getEmail();
		AppUser mentor = user(UserRole.MENTOR, "Their Mentor", null);
		Organization startup = organization("Solo Startup", founder);
		startup.setInviteCode("SOLO-" + UUID.randomUUID().toString().substring(0, 8));
		join(founder, startup);
		StoredDocument deck = document(founder.getId(), startup.getId(), DocumentKind.PITCH_DECK, null);
		Meeting held = meeting(startup, mentor, Instant.now().minus(5, ChronoUnit.DAYS), MeetingStatus.COMPLETED);
		StoredDocument meetingFile = document(founder.getId(), startup.getId(), DocumentKind.MEETING_FILE, held.getId());
		Meeting planned = meeting(startup, mentor, Instant.now().plus(5, ChronoUnit.DAYS), MeetingStatus.SCHEDULED);
		flushAndClear();

		delete(admin, founder.getId(), email).andExpect(status().isOk());

		Organization after = entityManager.find(Organization.class, startup.getId());
		assertThat(after.getArchivedAt()).isNotNull();
		assertThat(after.getFounder()).isNull();
		assertThat(after.getInviteCode()).isNull();
		assertThat(after.getName()).isEqualTo("Solo Startup");
		assertThat(entityManager.find(StoredDocument.class, deck.getId())).isNull();
		assertThat(entityManager.find(StoredDocument.class, meetingFile.getId())).isNotNull();
		assertThat(entityManager.find(Meeting.class, held.getId()).getStatus()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(entityManager.find(Meeting.class, planned.getId()).getStatus()).isEqualTo(MeetingStatus.CANCELLED);
		assertThat(count("select count(n) from Notification n where n.recipient.id = :id", mentor.getId())).isEqualTo(1);
	}

	// ---------------------------------------------------------------- signing up again

	@Test
	void nothingIsLeftForANewSignInWithTheSameAddressToLinkTo() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		String email = target.getEmail();
		identity(target);
		flushAndClear();

		delete(admin, target.getId(), email).andExpect(status().isOk());

		// The three ways a login is matched to an account: by uid, by verified address, by institution identity.
		assertThat(userRepository.findByFirebaseUid(UID)).isEmpty();
		assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
		assertThat(count("select count(i) from AppUserIdentity i where i.user.id = :id", target.getId())).isZero();

		// And the address is free: a fresh account with it can be created.
		AppUser again = new AppUser();
		again.setFullName("Deniz Kaya");
		again.setEmail(email);
		again.setRole(UserRole.MENTEE);
		again.setStatus(UserAccountStatus.PENDING);
		again.setFirebaseUid("a-new-uid");
		AppUser saved = userRepository.saveAndFlush(again);
		assertThat(saved.getId()).isNotEqualTo(target.getId());
		assertThat(saved.getOrganization()).isNull();
	}

	@Test
	void aDeletedAccountIsNotInTheAdminsAccountList() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		String email = target.getEmail();
		flushAndClear();

		delete(admin, target.getId(), email).andExpect(status().isOk());

		assertThat(appUserService.findAll(null, null)).noneMatch(row -> row.id().equals(target.getId()));
	}

	// ---------------------------------------------------------------- who may, and whom

	@Test
	void onlyAnAdminSignedInAsThemselvesMayDelete() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		AppUser coordinator = user(UserRole.COORDINATOR, "Coordinator Person", null);
		flushAndClear();

		mockMvc.perform(post("/api/admin/users/{id}/delete", target.getId())
				.contentType(MediaType.APPLICATION_JSON).content(body(target.getEmail())))
				.andExpect(status().isUnauthorized());
		delete(coordinator, target.getId(), target.getEmail()).andExpect(status().isForbidden());
		delete(target, target.getId(), target.getEmail()).andExpect(status().isForbidden());
		// The frontend's shared credentials cannot do it, whatever actor they name.
		mockMvc.perform(post("/api/admin/users/{id}/delete", target.getId())
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"confirmEmail\":\"" + target.getEmail() + "\",\"actorId\":\"" + admin.getId() + "\"}"))
				.andExpect(status().isForbidden());

		assertUntouched(target);
	}

	@Test
	void anAdminWhoIsNotApprovedMayNotDelete() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		admin.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(admin);
		flushAndClear();

		delete(admin, target.getId(), target.getEmail()).andExpect(status().isForbidden());

		assertUntouched(target);
	}

	@Test
	void anAdminCannotDeleteThemselvesOrAnotherAdmin() throws Exception {
		AppUser otherAdmin = user(UserRole.ADMIN, "Other Admin", "other-admin-uid");
		AppUser alsoAdmin = user(UserRole.MENTOR, "Mentor And Admin", "mentor-admin-uid");
		alsoAdmin.grant(UserRole.ADMIN, PermissionSource.ADMIN, Instant.now());
		userRepository.saveAndFlush(alsoAdmin);
		flushAndClear();

		delete(admin, admin.getId(), admin.getEmail())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(UserDeletionService.CODE_SELF));
		delete(admin, otherAdmin.getId(), otherAdmin.getEmail())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(UserDeletionService.CODE_ADMIN));
		delete(admin, alsoAdmin.getId(), alsoAdmin.getEmail())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(UserDeletionService.CODE_ADMIN));

		assertThat(userRepository.findById(admin.getId()).orElseThrow().getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		assertThat(userRepository.findById(otherAdmin.getId()).orElseThrow().getFirebaseUid()).isEqualTo("other-admin-uid");
		assertThat(userRepository.findById(alsoAdmin.getId()).orElseThrow().getFirebaseUid()).isEqualTo("mentor-admin-uid");
		assertThat(auditRepository.findByDeletedUserId(otherAdmin.getId())).isEmpty();
		assertThat(auditRepository.findByDeletedUserId(alsoAdmin.getId())).isEmpty();
	}

	@Test
	void theTypedAddressMustBeTheUsers() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		flushAndClear();

		delete(admin, target.getId(), "someone.else@example.test")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(UserDeletionService.CODE_EMAIL_MISMATCH));
		delete(admin, target.getId(), "   ").andExpect(status().isBadRequest());
		assertUntouched(target);

		// Case and surrounding spaces do not matter.
		delete(admin, target.getId(), "  " + target.getEmail().toUpperCase() + " ").andExpect(status().isOk());
	}

	@Test
	void aDeletedAccountCannotBeDeletedAgainAndAnUnknownOneIsNotFound() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		String email = target.getEmail();
		flushAndClear();
		delete(admin, target.getId(), email).andExpect(status().isOk());

		delete(admin, target.getId(), AppUser.deletedEmailFor(target.getId()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(UserDeletionService.CODE_ALREADY_DELETED));
		delete(admin, UUID.randomUUID(), email).andExpect(status().isNotFound());
		assertThat(auditRepository.findByDeletedUserId(target.getId())).hasSize(1);
	}

	// ---------------------------------------------------------------- failures outside the database

	@Test
	void whenFirebaseCannotBeAskedNothingIsChanged() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		flushAndClear();
		when(loginProvisioner.uidForEmail(anyString()))
				.thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Firebase is down"));

		delete(admin, target.getId(), target.getEmail()).andExpect(status().isBadGateway());

		assertUntouched(target);
		verify(loginProvisioner, never()).deleteLogin(anyString());
	}

	@Test
	void aLoginFoundOnlyByAddressIsRemovedToo() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", null);
		String email = target.getEmail();
		flushAndClear();
		when(loginProvisioner.uidForEmail(email)).thenReturn(Optional.of("login-never-linked"));

		delete(admin, target.getId(), email).andExpect(status().isOk());

		verify(loginProvisioner).deleteLogin("login-never-linked");
	}

	@Test
	void aFirebaseOrStorageFailureAfterTheCommitLeavesJobsThatTheRetryFinishes() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		String email = target.getEmail();
		StoredDocument cv = document(target.getId(), null, DocumentKind.CV, null);
		flushAndClear();
		doThrow(new IllegalStateException("Firebase could not delete the login: INTERNAL_ERROR"))
				.when(loginProvisioner).deleteLogin(UID);
		doThrow(new IllegalStateException("The file could not be deleted.")).when(objectStorage).delete(cv.getS3Key());

		delete(admin, target.getId(), email)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cleanupPending").value(true));

		// The account is gone all the same; only the outside leftovers wait.
		assertThat(userRepository.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(UserAccountStatus.DELETED);
		UserDeletionAudit audit = auditRepository.findByDeletedUserId(target.getId()).getFirst();
		assertThat(audit.getCleanupCompletedAt()).isNull();
		entityManager.clear();
		List<UserDeletionCleanup> waiting = cleanupRepository.findByAuditIdOrderByCreatedAtAsc(audit.getId());
		assertThat(waiting).extracting(UserDeletionCleanup::getKind)
				.containsExactlyInAnyOrder(UserDeletionCleanupKind.FIREBASE_LOGIN, UserDeletionCleanupKind.STORED_OBJECT);
		assertThat(waiting).allSatisfy(job -> {
			assertThat(job.getAttempts()).isEqualTo(1);
			assertThat(job.getNextAttemptAt()).isAfter(Instant.now());
			assertThat(job.getLastError()).isNotBlank();
		});

		// Not due yet: the retry leaves them alone.
		cleanupService.retryDue();
		assertThat(cleanupRepository.existsByAuditId(audit.getId())).isTrue();

		doNothing().when(loginProvisioner).deleteLogin(UID);
		doNothing().when(objectStorage).delete(cv.getS3Key());
		makeEveryJobDue();
		cleanupService.retryDue();

		flushAndClear();
		assertThat(cleanupRepository.existsByAuditId(audit.getId())).isFalse();
		assertThat(auditRepository.findById(audit.getId()).orElseThrow().getCleanupCompletedAt()).isNotNull();
	}

	@Test
	void aLoginThatANewAccountUsesAgainIsKept() throws Exception {
		AppUser target = user(UserRole.MENTEE, "Deniz Kaya", UID);
		String email = target.getEmail();
		flushAndClear();
		doThrow(new IllegalStateException("Firebase could not delete the login: INTERNAL_ERROR"))
				.when(loginProvisioner).deleteLogin(UID);
		delete(admin, target.getId(), email).andExpect(jsonPath("$.cleanupPending").value(true));
		clearInvocations(loginProvisioner);

		// The person signs up again with the same login before the retry runs.
		AppUser again = new AppUser();
		again.setFullName("Deniz Kaya");
		again.setEmail(email);
		again.setRole(UserRole.MENTEE);
		again.setFirebaseUid(UID);
		userRepository.saveAndFlush(again);
		makeEveryJobDue();

		cleanupService.retryDue();

		verify(loginProvisioner, never()).deleteLogin(any());
		assertThat(cleanupRepository.count()).isZero();
		assertThat(userRepository.findByFirebaseUid(UID)).isPresent();
	}

	// ---------------------------------------------------------------- helpers

	private ResultActions delete(AppUser actor, UUID targetId, String confirmEmail) throws Exception {
		return mockMvc.perform(post("/api/admin/users/{id}/delete", targetId)
				.with(authentication(signedIn(actor)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(confirmEmail)));
	}

	private static String body(String confirmEmail) {
		return "{\"confirmEmail\":\"" + confirmEmail + "\"}";
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "signed-in-uid"), null, List.of());
	}

	private void makeEveryJobDue() {
		entityManager.createQuery("update UserDeletionCleanup c set c.nextAttemptAt = :past")
				.setParameter("past", Instant.now().minus(1, ChronoUnit.MINUTES))
				.executeUpdate();
		entityManager.clear();
	}

	private void assertUntouched(AppUser target) {
		entityManager.clear();
		AppUser row = userRepository.findById(target.getId()).orElseThrow();
		assertThat(row.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		assertThat(row.getFullName()).isEqualTo(target.getFullName());
		assertThat(row.getEmail()).isEqualTo(target.getEmail());
		assertThat(row.getFirebaseUid()).isEqualTo(target.getFirebaseUid());
		assertThat(auditRepository.findByDeletedUserId(target.getId())).isEmpty();
	}

	private long count(String jpql, UUID id) {
		return entityManager.createQuery(jpql, Long.class).setParameter("id", id).getSingleResult();
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private AppUser user(UserRole role, String fullName, String firebaseUid) {
		AppUser user = new AppUser();
		user.setFullName(fullName);
		user.setEmail(UUID.randomUUID() + "@deletion.test");
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
		join(member, organization, Instant.now().minus(1, ChronoUnit.DAYS));
	}

	private void join(AppUser member, Organization organization, Instant joinedAt) {
		member.setOrganization(organization);
		userRepository.saveAndFlush(member);
		TeamMembership membership = new TeamMembership();
		membership.setUserId(member.getId());
		membership.setOrganization(organization);
		membership.setJoinedAt(joinedAt);
		entityManager.persist(membership);
	}

	private void identity(AppUser user) {
		AppUserIdentity identity = new AppUserIdentity();
		identity.setUser(user);
		identity.setProvider("odtu");
		identity.setPersistentId("persistent-" + user.getId());
		identity.setLinkedAt(Instant.now());
		identity.setLastLoginAt(Instant.now());
		entityManager.persist(identity);
	}

	private void draft(String firebaseUid) {
		OnboardingDraft draft = new OnboardingDraft();
		draft.setFirebaseUid(firebaseUid);
		draft.setKind("signup-wizard");
		draft.setStep("profile");
		draft.setPayload("{}");
		entityManager.persist(draft);
	}

	private void feedback(AppUser author) {
		PlatformFeedback feedback = new PlatformFeedback();
		feedback.setAuthor(author);
		feedback.setKind(PlatformFeedbackKind.SUGGESTION);
		feedback.setSubject("A subject");
		feedback.setMessage("A message");
		feedback.setLanguage(AppLanguage.EN);
		entityManager.persist(feedback);
	}

	private StoredDocument document(UUID userId, UUID organizationId, DocumentKind kind, UUID meetingId) {
		StoredDocument document = new StoredDocument();
		document.setId(UUID.randomUUID());
		document.setUserId(userId);
		document.setOrganizationId(organizationId);
		document.setKind(kind.value());
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
		request.setUrgency(MentorshipRequestUrgency.values()[0]);
		request.setMentorshipFormat(MentorshipRequestFormat.values()[0]);
		request.setMeetingMode(MeetingMode.values()[0]);
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
		mentorship.setStartDate(LocalDate.now().minusDays(10));
		entityManager.persist(mentorship);
		return mentorship;
	}
}
