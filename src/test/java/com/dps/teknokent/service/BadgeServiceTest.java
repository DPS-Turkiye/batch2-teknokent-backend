package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.BadgeStatus;
import com.dps.teknokent.domain.GateBadgeState;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AppUserPermission;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.UserBadge;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.UserBadgeRepository;

@ExtendWith(MockitoExtension.class)
class BadgeServiceTest {

	@Mock
	private UserBadgeRepository awards;
	@Mock
	private AppUserRepository users;
	@Mock
	private ProgramRepository programs;
	@Mock
	private ProgramMentorRepository programMentors;
	@Mock
	private CoordinatorProgramService coordinatorProgramService;
	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private NotificationService notificationService;
	@Mock
	private com.dps.teknokent.repository.TrainingModuleRepository trainingModules;
	@Mock
	private com.dps.teknokent.repository.TrainingProgressRepository trainingProgress;
	@Mock
	private com.dps.teknokent.repository.TrainingAttemptRepository trainingAttempts;

	@InjectMocks
	private BadgeService service;

	private final AppUser coordinator = user("Elif Koordinatör", UserRole.COORDINATOR);
	private final Program iskur = program("İŞKUR 2026 Bahar", ProgramType.PEER_MENTORING);
	private final AppUser student = student(iskur);

	@BeforeEach
	void signIn() {
		lenient().when(coordinatorProgramService.requireManagedProgram(coordinator.getId(), iskur.getId()))
				.thenReturn(iskur);
		lenient().when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		lenient().when(domainLookupService.requireUser(student.getId())).thenReturn(student);
		lenient().when(awards.findFirstByUserIdAndBadgeCodeAndProgramIdAndStatus(any(), any(), any(), any()))
				.thenReturn(Optional.empty());
		lenient().when(programMentors.findByProgramIdAndMentorId(any(), any())).thenReturn(Optional.empty());
	}

	@Test
	void awardingTheGateBadgeGrantsMentorMakesThemAProgramMentorAndTellsThem() {
		var row = service.awardGate(coordinator.getId(), iskur.getId(), student.getId());

		ArgumentCaptor<UserBadge> saved = ArgumentCaptor.forClass(UserBadge.class);
		verify(awards).save(saved.capture());
		UserBadge award = saved.getValue();
		assertThat(award.getBadgeCode()).isEqualTo(BadgeCode.PEER_MENTOR);
		assertThat(award.getProgramId()).isEqualTo(iskur.getId());
		assertThat(award.getSource()).isEqualTo(BadgeSource.COORDINATOR);
		assertThat(award.getAwardedBy()).isEqualTo(coordinator.getId());
		assertThat(row.state()).isEqualTo(GateBadgeState.ACTIVE);
		assertThat(row.awardId()).isEqualTo(award.getId());

		assertThat(student.has(UserRole.MENTOR)).isTrue();
		AppUserPermission mentor = grant(student, UserRole.MENTOR);
		assertThat(mentor.getSource()).isEqualTo(PermissionSource.BADGE);
		assertThat(mentor.getSourceRef()).isEqualTo(award.getId());

		ArgumentCaptor<ProgramMentor> membership = ArgumentCaptor.forClass(ProgramMentor.class);
		verify(programMentors).save(membership.capture());
		assertThat(membership.getValue().getStatus()).isEqualTo(ProgramMentorStatus.ACCEPTED);
		assertThat(membership.getValue().getMentorId()).isEqualTo(student.getId());

		verify(notificationService).create(eq(student), eq(NotificationCategory.BADGE), eq("Rozet kazanıldı"),
				anyString(), eq("/student/peer-mentoring"));
	}

	@Test
	void anAdminAwardsAsAdmin() {
		AppUser admin = user("Arya Admin", UserRole.ADMIN);
		when(coordinatorProgramService.requireManagedProgram(admin.getId(), iskur.getId())).thenReturn(iskur);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);

		assertThat(service.awardGate(admin.getId(), iskur.getId(), student.getId()).source())
				.isEqualTo(BadgeSource.ADMIN);
	}

	@Test
	void theGateBadgeExistsOnlyInPeerPrograms() {
		Program startups = program("Girişim 2026", ProgramType.STARTUP_MENTORING);
		when(coordinatorProgramService.requireManagedProgram(coordinator.getId(), startups.getId()))
				.thenReturn(startups);

		assertThatThrownBy(() -> service.awardGate(coordinator.getId(), startups.getId(), student.getId()))
				.isInstanceOf(InvalidRequestException.class);
		verify(awards, never()).save(any());
	}

	@Test
	void nobodyAwardsTheirOwnBadgeOrAwardsSomeoneOutsideTheProgram() {
		assertThatThrownBy(() -> service.awardGate(coordinator.getId(), iskur.getId(), coordinator.getId()))
				.isInstanceOf(InvalidRequestException.class);

		AppUser outsider = student(program("Başka Program", ProgramType.PEER_MENTORING));
		when(domainLookupService.requireUser(outsider.getId())).thenReturn(outsider);
		assertThatThrownBy(() -> service.awardGate(coordinator.getId(), iskur.getId(), outsider.getId()))
				.isInstanceOf(InvalidRequestException.class);

		AppUser pending = student(iskur);
		pending.setStatus(UserAccountStatus.PENDING);
		when(domainLookupService.requireUser(pending.getId())).thenReturn(pending);
		assertThatThrownBy(() -> service.awardGate(coordinator.getId(), iskur.getId(), pending.getId()))
				.isInstanceOf(InvalidRequestException.class);
		verify(awards, never()).save(any());
	}

	// Joined by the coordinator's invitation, so never through "Kabul Et".
	@Test
	void anAcceptedMentorOfTheProgramCanBeAwardedTooButNotAPendingOne() {
		AppUser invited = user("Ayşe Mentor", UserRole.MENTOR);
		when(domainLookupService.requireUser(invited.getId())).thenReturn(invited);
		ProgramMentor pending = new ProgramMentor();
		pending.setProgramId(iskur.getId());
		pending.setMentorId(invited.getId());
		pending.setStatus(ProgramMentorStatus.PENDING);
		when(programMentors.findByProgramIdAndMentorId(iskur.getId(), invited.getId()))
				.thenReturn(Optional.of(pending));

		assertThatThrownBy(() -> service.awardGate(coordinator.getId(), iskur.getId(), invited.getId()))
				.isInstanceOf(InvalidRequestException.class);

		pending.setStatus(ProgramMentorStatus.ACCEPTED);
		assertThat(service.awardGate(coordinator.getId(), iskur.getId(), invited.getId()).state())
				.isEqualTo(GateBadgeState.ACTIVE);
	}

	@Test
	void anActiveBadgeIsNotAwardedTwice() {
		when(awards.findFirstByUserIdAndBadgeCodeAndProgramIdAndStatus(student.getId(), BadgeCode.PEER_MENTOR,
				iskur.getId(), BadgeStatus.ACTIVE)).thenReturn(Optional.of(active(student, iskur)));

		assertThatThrownBy(() -> service.awardGate(coordinator.getId(), iskur.getId(), student.getId()))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void anAchievementGivesNoPermissionOrMembershipAndSaysWhereToSeeIt() {
		service.award(student, BadgeCode.FIRST_SESSION, null, BadgeSource.RULE, null, true);

		ArgumentCaptor<UserBadge> saved = ArgumentCaptor.forClass(UserBadge.class);
		verify(awards).save(saved.capture());
		assertThat(saved.getValue().getProgramId()).isNull();
		assertThat(saved.getValue().getSource()).isEqualTo(BadgeSource.RULE);
		assertThat(student.has(UserRole.MENTOR)).isFalse();
		verify(programMentors, never()).save(any());
		verify(notificationService).create(eq(student), eq(NotificationCategory.BADGE), eq("Yeni rozet: İlk Görüşme"),
				anyString(), eq("/student/profile"));
	}

	@Test
	void aSilentAwardSendsNoNotification() {
		service.award(student, BadgeCode.ACTIVE_MENTEE, null, BadgeSource.RULE, null, false);

		verify(awards).save(any());
		verifyNoInteractions(notificationService);
	}

	@Test
	void revokingNeedsAReasonAndTakesBackTheMentorPermissionTheBadgeGave() {
		UserBadge award = awardedThroughService();
		when(awards.findById(award.getId())).thenReturn(Optional.of(award));

		assertThatThrownBy(() -> service.revoke(coordinator.getId(), iskur.getId(), award.getId(), "  "))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(award.getStatus()).isEqualTo(BadgeStatus.ACTIVE);

		when(awards.findByUserIdAndStatusOrderByAwardedAtDesc(student.getId(), BadgeStatus.ACTIVE))
				.thenReturn(List.of());
		var row = service.revoke(coordinator.getId(), iskur.getId(), award.getId(), " Görüşmelere gelmedi ");

		assertThat(row.state()).isEqualTo(GateBadgeState.REVOKED);
		assertThat(award.getReason()).isEqualTo("Görüşmelere gelmedi");
		assertThat(award.getRevokedBy()).isEqualTo(coordinator.getId());
		assertThat(student.has(UserRole.MENTOR)).isFalse();
		assertThat(student.has(UserRole.MENTEE)).isTrue();
		verify(notificationService).create(eq(student), eq(NotificationCategory.BADGE), eq("Rozetin geri alındı"),
				org.mockito.ArgumentMatchers.contains("Görüşmelere gelmedi"), anyString());
	}

	// The gate badge of another program still backs MENTOR, so revoking one of two keeps it.
	@Test
	void revokingOneOfTwoGateBadgesKeepsTheMentorPermission() {
		UserBadge award = awardedThroughService();
		when(awards.findById(award.getId())).thenReturn(Optional.of(award));
		when(awards.findByUserIdAndStatusOrderByAwardedAtDesc(student.getId(), BadgeStatus.ACTIVE))
				.thenReturn(List.of(active(student, program("Diğer Program", ProgramType.PEER_MENTORING))));

		service.revoke(coordinator.getId(), iskur.getId(), award.getId(), "Program değişti");

		assertThat(student.has(UserRole.MENTOR)).isTrue();
	}

	// A mentor account accepted through Onaylar holds MENTOR as its own role; the badge never gave it.
	@Test
	void revokingNeverTakesAMentorPermissionTheBadgeDidNotGive() {
		AppUser mentorAccount = user("Ayşe Mentor", UserRole.MENTOR);
		when(domainLookupService.requireUser(mentorAccount.getId())).thenReturn(mentorAccount);
		UserBadge award = active(mentorAccount, iskur);
		when(awards.findById(award.getId())).thenReturn(Optional.of(award));
		when(awards.findByUserIdAndStatusOrderByAwardedAtDesc(mentorAccount.getId(), BadgeStatus.ACTIVE))
				.thenReturn(List.of());

		service.revoke(coordinator.getId(), iskur.getId(), award.getId(), "Yanlış program");

		assertThat(mentorAccount.has(UserRole.MENTOR)).isTrue();
	}

	@Test
	void anAwardOfAnotherProgramOrAnAlreadyRevokedOneCannotBeRevokedHere() {
		UserBadge elsewhere = active(student, program("Diğer Program", ProgramType.PEER_MENTORING));
		when(awards.findById(elsewhere.getId())).thenReturn(Optional.of(elsewhere));
		assertThatThrownBy(() -> service.revoke(coordinator.getId(), iskur.getId(), elsewhere.getId(), "x"))
				.isInstanceOf(com.dps.teknokent.exception.ResourceNotFoundException.class);

		UserBadge revoked = active(student, iskur);
		revoked.setStatus(BadgeStatus.REVOKED);
		revoked.setRevokedAt(Instant.now());
		when(awards.findById(revoked.getId())).thenReturn(Optional.of(revoked));
		assertThatThrownBy(() -> service.revoke(coordinator.getId(), iskur.getId(), revoked.getId(), "x"))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void acceptingAMentorInAPeerProgramAwardsTheBadgeOnceAndNowhereElse() {
		AppUser mentorAccount = user("Ayşe Mentor", UserRole.MENTOR);

		service.awardAfterMentorApproval(coordinator.getId(), iskur, mentorAccount);
		verify(awards).save(any(UserBadge.class));

		when(awards.findFirstByUserIdAndBadgeCodeAndProgramIdAndStatus(mentorAccount.getId(), BadgeCode.PEER_MENTOR,
				iskur.getId(), BadgeStatus.ACTIVE)).thenReturn(Optional.of(active(mentorAccount, iskur)));
		service.awardAfterMentorApproval(coordinator.getId(), iskur, mentorAccount);
		service.awardAfterMentorApproval(coordinator.getId(), program("Girişim", ProgramType.STARTUP_MENTORING),
				mentorAccount);
		verify(awards, org.mockito.Mockito.times(1)).save(any(UserBadge.class));
	}

	@Test
	void theRosterListsTheProgramsStudentsAndBadgeHoldersWithTheirStateAndFilters() {
		AppUser waiting = student(iskur);
		AppUser mentorAccount = user("Ayşe Mentor", UserRole.MENTOR);
		UserBadge studentAward = active(student, iskur);
		UserBadge mentorAward = active(mentorAccount, iskur);
		when(awards.findByProgramIdAndBadgeCodeOrderByAwardedAtDesc(iskur.getId(), BadgeCode.PEER_MENTOR))
				.thenReturn(List.of(studentAward, mentorAward));
		when(users.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(iskur.getId(), UserRole.MENTEE,
				UserAccountStatus.APPROVED)).thenReturn(List.of(student, waiting));
		AppUser invited = user("Can Davetli", UserRole.MENTOR);
		ProgramMentor accepted = new ProgramMentor();
		accepted.setMentorId(invited.getId());
		accepted.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentors.findByProgramIdOrderByCreatedAtAsc(iskur.getId())).thenReturn(List.of(accepted));
		when(users.findAllById(any())).thenReturn(List.of(student, mentorAccount, invited));

		var all = service.roster(coordinator.getId(), iskur.getId(), null);
		assertThat(all).extracting(row -> row.state()).containsExactlyInAnyOrder(GateBadgeState.ACTIVE,
				GateBadgeState.NONE, GateBadgeState.ACTIVE, GateBadgeState.NONE);
		assertThat(all).extracting(row -> row.userId()).contains(invited.getId());

		var mentors = service.roster(coordinator.getId(), iskur.getId(), GateBadgeState.ACTIVE);
		assertThat(mentors).extracting(row -> row.userId())
				.containsExactlyInAnyOrder(student.getId(), mentorAccount.getId());
	}

	// 4T-3: Katılımcılar shows how far someone got in the training on its current version.
	@Test
	void theRosterShowsTrainingProgressOnTheCurrentVersion() {
		com.dps.teknokent.entity.TrainingModule module = new com.dps.teknokent.entity.TrainingModule();
		module.setId(UUID.randomUUID());
		module.setCurrentVersion(2);
		when(trainingModules.findByProgramId(iskur.getId())).thenReturn(Optional.of(module));
		when(users.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(iskur.getId(), UserRole.MENTEE,
				UserAccountStatus.APPROVED)).thenReturn(List.of(student));
		com.dps.teknokent.entity.TrainingProgress watched = new com.dps.teknokent.entity.TrainingProgress();
		watched.setUserId(student.getId());
		when(trainingProgress.findByModuleIdAndVersionAndUserIdIn(eq(module.getId()), eq(2), any()))
				.thenReturn(List.of(watched));
		List<com.dps.teknokent.entity.TrainingAttempt> tries = List.of(6, 7).stream().map(score -> {
			com.dps.teknokent.entity.TrainingAttempt attempt = new com.dps.teknokent.entity.TrainingAttempt();
			attempt.setUserId(student.getId());
			attempt.setScore(score);
			return attempt;
		}).toList();
		when(trainingAttempts.findByModuleIdAndVersionAndUserIdIn(eq(module.getId()), eq(2), any()))
				.thenReturn(tries);

		assertThat(service.roster(coordinator.getId(), iskur.getId(), null)).singleElement().satisfies(row -> {
			assertThat(row.training().videoWatched()).isTrue();
			assertThat(row.training().attempts()).isEqualTo(2);
			assertThat(row.training().bestScore()).isEqualTo(7);
			assertThat(row.training().started()).isTrue();
		});
	}

	@Test
	void theStudentListIsForAdminsOnly() {
		assertThatThrownBy(() -> service.students(coordinator.getId(), null))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void everyoneSeesOnlyActiveBadges() {
		UserBadge award = active(student, iskur);
		when(awards.findByUserIdAndStatusOrderByAwardedAtDesc(student.getId(), BadgeStatus.ACTIVE))
				.thenReturn(List.of(award));

		assertThat(service.publicOf(student.getId())).singleElement().satisfies(badge -> {
			assertThat(badge.code()).isEqualTo(BadgeCode.PEER_MENTOR);
			assertThat(badge.programId()).isEqualTo(iskur.getId());
		});
		verify(awards, never()).findByUserIdOrderByAwardedAtDesc(any());
	}

	@Test
	void theHolderSeesEveryAwardWithItsProgramAndReason() {
		UserBadge revoked = active(student, iskur);
		revoked.setStatus(BadgeStatus.REVOKED);
		revoked.setRevokedAt(Instant.now());
		revoked.setReason("Görüşmelere gelmedi");
		when(awards.findByUserIdOrderByAwardedAtDesc(student.getId())).thenReturn(List.of(revoked));
		when(programs.findAllById(List.of(iskur.getId()))).thenReturn(List.of(iskur));

		assertThat(service.mine(student.getId())).singleElement().satisfies(award -> {
			assertThat(award.programName()).isEqualTo("İŞKUR 2026 Bahar");
			assertThat(award.reason()).isEqualTo("Görüşmelere gelmedi");
			assertThat(award.status()).isEqualTo(BadgeStatus.REVOKED);
		});
	}

	private UserBadge awardedThroughService() {
		service.awardGate(coordinator.getId(), iskur.getId(), student.getId());
		ArgumentCaptor<UserBadge> saved = ArgumentCaptor.forClass(UserBadge.class);
		verify(awards).save(saved.capture());
		return saved.getValue();
	}

	private static UserBadge active(AppUser holder, Program program) {
		UserBadge award = new UserBadge();
		award.setId(UUID.randomUUID());
		award.setUserId(holder.getId());
		award.setBadgeCode(BadgeCode.PEER_MENTOR);
		award.setProgramId(program.getId());
		award.setStatus(BadgeStatus.ACTIVE);
		award.setSource(BadgeSource.COORDINATOR);
		award.setAwardedAt(Instant.now());
		return award;
	}

	@SuppressWarnings("unchecked")
	private static AppUserPermission grant(AppUser user, UserRole permission) {
		return ((List<AppUserPermission>) ReflectionTestUtils.getField(user, "permissions")).stream()
				.filter(grant -> grant.getPermission() == permission)
				.findFirst()
				.orElseThrow();
	}

	private static AppUser student(Program program) {
		AppUser student = user("Zeynep Öğrenci", UserRole.MENTEE);
		student.setProgramId(program.getId());
		return student;
	}

	private static AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setEmail(user.getId() + "@odtu-dev.invalid");
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}

	private static Program program(String name, ProgramType type) {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName(name);
		program.setProgramType(type);
		return program;
	}
}
