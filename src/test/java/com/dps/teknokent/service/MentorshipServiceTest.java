package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipGoalRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
import com.dps.teknokent.dto.requests.UpdateMentorshipGoalRequest;
import com.dps.teknokent.dto.requests.UpdateMentorshipRequest;
import com.dps.teknokent.dto.requests.UpsertMentorshipWeekCheckRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipGoal;
import com.dps.teknokent.entity.MentorshipWeekCheck;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipGoalRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipWeekCheckRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.Texts;

@ExtendWith(MockitoExtension.class)
class MentorshipServiceTest {

	@Mock
	private MentorshipRepository mentorshipRepository;

	@Mock
	private MentorshipGoalRepository mentorshipGoalRepository;

	@Mock
	private MentorshipWeekCheckRepository mentorshipWeekCheckRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private DomainLookupService domainLookupService;

	@Mock
	private NotificationService notificationService;
	@Mock
	private MenteeProgramService menteeProgramService;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private StartupAccessService startupAccessService;

	@InjectMocks
	private MentorshipService mentorshipService;

	@Test
	void findAll_returnsAllMentorshipsOrderedByCreatedAtDesc() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");

		Mentorship mentorship = mentorship(
				mentorshipId,
				mentorId,
				organizationId,
				"Product strategy coaching",
				MentorshipStatus.ACTIVE,
				LocalDate.of(2026, 8, 1),
				LocalDate.of(2026, 9, 30),
				1);

		when(mentorshipRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(mentorship));
		when(mentorshipGoalRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorshipId))
				.thenReturn(List.of());

		var result = mentorshipService.findAll();

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().mentorId()).isEqualTo(mentorId);
		assertThat(result.getFirst().organizationId()).isEqualTo(organizationId);
		assertThat(result.getFirst().status()).isEqualTo(MentorshipStatus.ACTIVE);
		assertThat(result.getFirst().daysPerWeek()).isEqualTo(1);
		assertThat(result.getFirst().progressPercent()).isZero();
	}

	@Test
	void create_rejectsEndDateBeforeStartDate() {
		CreateMentorshipRequest request = new CreateMentorshipRequest(
				UUID.fromString("00000000-0000-0000-0000-000000000201"),
				UUID.fromString("00000000-0000-0000-0000-000000000301"),
				MentorshipStatus.PLANNED,
				LocalDate.of(2026, 9, 10),
				LocalDate.of(2026, 9, 1),
				1,
				"Roadmap guidance");

		assertThatThrownBy(() -> mentorshipService.create(request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("endDate must be on or after startDate");
	}

	@Test
	void create_requiresMentorRolePersistsAndNotifiesOrgMentees() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID menteeId = UUID.fromString("00000000-0000-0000-0000-000000000501");

		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		AppUser mentee = user(menteeId, "mentee@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		Organization organization = organization(organizationId, "Teknokent");
		CreateMentorshipRequest request = new CreateMentorshipRequest(
				mentorId,
				organizationId,
				MentorshipStatus.ACTIVE,
				LocalDate.of(2026, 8, 10),
				LocalDate.of(2026, 12, 10),
				2,
				"Build a scalable product strategy");

		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
		when(mentorshipRepository.save(any(Mentorship.class))).thenAnswer(invocation -> {
			Mentorship saved = invocation.getArgument(0);
			saved.setId(mentorshipId);
			return saved;
		});
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId))
				.thenReturn(List.of(mentee, mentor));
		when(mentorshipGoalRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorshipId))
				.thenReturn(List.of());

		var result = mentorshipService.create(request);

		assertThat(result.id()).isEqualTo(mentorshipId);
		assertThat(result.daysPerWeek()).isEqualTo(2);
		verify(notificationService).create(
				eq(mentee),
				eq("Düzenli mentorlük başladı"),
				org.mockito.ArgumentMatchers.contains("Teknokent"),
				eq("/mentorships?tab=regular"));
	}

	@Test
	void create_rejectsNonMentorUsers() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");

		AppUser nonMentor = user(mentorId, "mentee@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		CreateMentorshipRequest request = new CreateMentorshipRequest(
				mentorId,
				organizationId,
				MentorshipStatus.PLANNED,
				LocalDate.of(2026, 8, 10),
				null,
				1,
				"Get advice");

		when(domainLookupService.requireUser(mentorId)).thenReturn(nonMentor);

		assertThatThrownBy(() -> mentorshipService.create(request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("MENTOR role");
	}

	@Test
	void findActive_filtersByMentorAndDateWindow() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		Mentorship mentorship = mentorship(
				mentorshipId,
				mentorId,
				organizationId,
				null,
				MentorshipStatus.ACTIVE,
				LocalDate.now(MeetingSlotTimes.APP_ZONE).minusDays(1),
				LocalDate.now(MeetingSlotTimes.APP_ZONE).plusDays(10),
				1);

		when(mentorshipRepository.findActiveForMentorOn(
				eq(mentorId), eq(MentorshipStatus.ACTIVE), any(LocalDate.class)))
				.thenReturn(List.of(mentorship));
		when(mentorshipGoalRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorshipId))
				.thenReturn(List.of());

		assertThat(mentorshipService.findActive(mentorId, null)).hasSize(1);
	}

	@Test
	void listWeekChecks_excludesMentorshipsOutsideWeek() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		LocalDate weekStart = LocalDate.of(2026, 12, 21);

		when(mentorshipRepository.findOverlappingWeekForMentor(
				eq(mentorId),
				eq(MentorshipStatus.ACTIVE),
				eq(weekStart),
				eq(weekStart.plusDays(6))))
				.thenReturn(List.of());

		assertThat(mentorshipService.listWeekChecks(mentorId, weekStart)).isEmpty();
	}

	@Test
	void addAndUpdateGoal_updatesProgress() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID goalId = UUID.fromString("00000000-0000-0000-0000-000000000601");
		Mentorship mentorship = mentorship(
				mentorshipId,
				mentorId,
				organizationId,
				null,
				MentorshipStatus.ACTIVE,
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 31),
				1);

		when(mentorshipRepository.findById(mentorshipId)).thenReturn(Optional.of(mentorship));
		when(mentorshipGoalRepository.countByMentorshipId(mentorshipId)).thenReturn(0L);
		when(mentorshipGoalRepository.save(any(MentorshipGoal.class))).thenAnswer(invocation -> {
			MentorshipGoal goal = invocation.getArgument(0);
			goal.setId(goalId);
			return goal;
		});

		var created = mentorshipService.addGoal(
				mentorshipId, mentorId, new CreateMentorshipGoalRequest("PMF"));
		assertThat(created.title()).isEqualTo("PMF");
		assertThat(created.done()).isFalse();

		MentorshipGoal existing = MentorshipGoal.create(mentorship, "PMF", 0);
		existing.setId(goalId);
		when(mentorshipGoalRepository.findById(goalId)).thenReturn(Optional.of(existing));
		when(mentorshipGoalRepository.save(existing)).thenReturn(existing);

		var updated = mentorshipService.updateGoal(
				mentorshipId, goalId, mentorId, new UpdateMentorshipGoalRequest(true));
		assertThat(updated.done()).isTrue();
	}

	@Test
	void upsertWeekCheck_persistsCompletion() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		LocalDate weekStart = LocalDate.of(2026, 9, 7);
		Mentorship mentorship = mentorship(
				mentorshipId,
				mentorId,
				organizationId,
				null,
				MentorshipStatus.ACTIVE,
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 20),
				1);

		when(mentorshipRepository.findById(mentorshipId)).thenReturn(Optional.of(mentorship));
		when(mentorshipWeekCheckRepository.findByMentorshipIdAndWeekStart(mentorshipId, weekStart))
				.thenReturn(Optional.empty());
		when(mentorshipWeekCheckRepository.save(any(MentorshipWeekCheck.class))).thenAnswer(invocation -> {
			MentorshipWeekCheck check = invocation.getArgument(0);
			check.setId(UUID.randomUUID());
			return check;
		});

		var result = mentorshipService.upsertWeekCheck(
				mentorshipId,
				mentorId,
				new UpsertMentorshipWeekCheckRequest(weekStart, true));

		assertThat(result.completed()).isTrue();
		assertThat(result.weekStart()).isEqualTo(weekStart);
	}

	@Test
	void update_changesDatesAndDaysPerWeek() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		Mentorship mentorship = mentorship(
				mentorshipId,
				mentorId,
				organizationId,
				null,
				MentorshipStatus.ACTIVE,
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 6, 1),
				1);

		when(mentorshipRepository.findById(mentorshipId)).thenReturn(Optional.of(mentorship));
		when(mentorshipRepository.save(mentorship)).thenReturn(mentorship);
		when(mentorshipGoalRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorshipId))
				.thenReturn(List.of());

		var result = mentorshipService.update(
				mentorshipId,
				mentorId,
				new UpdateMentorshipRequest(
						LocalDate.of(2026, 2, 1),
						LocalDate.of(2026, 12, 20),
						3));

		assertThat(result.startDate()).isEqualTo(LocalDate.of(2026, 2, 1));
		assertThat(result.endDate()).isEqualTo(LocalDate.of(2026, 12, 20));
		assertThat(result.daysPerWeek()).isEqualTo(3);
	}

	@Test
	void update_rejectsEndDateBeforeStartDate() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		Mentorship mentorship = mentorship(
				mentorshipId,
				mentorId,
				organizationId,
				null,
				MentorshipStatus.ACTIVE,
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 6, 1),
				1);

		when(mentorshipRepository.findById(mentorshipId)).thenReturn(Optional.of(mentorship));

		assertThatThrownBy(() -> mentorshipService.update(
				mentorshipId,
				mentorId,
				new UpdateMentorshipRequest(
						LocalDate.of(2026, 12, 1),
						LocalDate.of(2026, 1, 1),
						1)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("endDate must be on or after startDate");
	}

	@Test
	void delete_removesOwnedMentorship() {
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorshipId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		Mentorship mentorship = mentorship(
				mentorshipId,
				mentorId,
				organizationId,
				null,
				MentorshipStatus.ACTIVE,
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 6, 1),
				1);

		when(mentorshipRepository.findById(mentorshipId)).thenReturn(Optional.of(mentorship));

		mentorshipService.delete(mentorshipId, mentorId);

		verify(mentorshipRepository).delete(mentorship);
	}

	// --- Lists and single lookups ---

	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private static final UUID OTHER_MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
	private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private static final UUID MENTORSHIP_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");

	private static Mentorship mentorshipBetween(LocalDate startDate, LocalDate endDate) {
		return mentorship(MENTORSHIP_ID, MENTOR_ID, ORGANIZATION_ID, null, MentorshipStatus.ACTIVE, startDate,
				endDate, 3);
	}

	@Test
	void findActive_needsExactlyOneOfMentorOrOrganization() {
		assertThatThrownBy(() -> mentorshipService.findActive(null, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("mentorId or organizationId is required");
		assertThatThrownBy(() -> mentorshipService.findActive(MENTOR_ID, ORGANIZATION_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Provide only one");
		verifyNoInteractions(mentorshipRepository);
	}

	@Test
	void findActive_forAStartupAsksForItsMentorshipsActiveToday() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), null);
		when(mentorshipRepository.findActiveForOrganizationOn(eq(ORGANIZATION_ID), eq(MentorshipStatus.ACTIVE),
				any(LocalDate.class))).thenReturn(List.of(mentorship));
		LocalDate before = LocalDate.now(MeetingSlotTimes.APP_ZONE);

		var result = mentorshipService.findActive(null, ORGANIZATION_ID);

		assertThat(result).extracting(item -> item.id()).containsExactly(MENTORSHIP_ID);
		ArgumentCaptor<LocalDate> today = ArgumentCaptor.forClass(LocalDate.class);
		verify(mentorshipRepository).findActiveForOrganizationOn(eq(ORGANIZATION_ID), eq(MentorshipStatus.ACTIVE),
				today.capture());
		assertThat(today.getValue()).isBetween(before, LocalDate.now(MeetingSlotTimes.APP_ZONE));
		verify(mentorshipRepository, never()).findActiveForMentorOn(any(), any(), any());
	}

	@Test
	void findById_returnsTheMentorshipWithItsGoalsAndHowFarTheyAre() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), null);
		MentorshipGoal done = MentorshipGoal.create(mentorship, "PMF", 0);
		done.setDone(true);
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));
		when(mentorshipGoalRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(MENTORSHIP_ID))
				.thenReturn(List.of(done, MentorshipGoal.create(mentorship, "İlk satış", 1),
						MentorshipGoal.create(mentorship, "Yatırım sunumu", 2)));

		var result = mentorshipService.findById(MENTORSHIP_ID, null);

		assertThat(result.goalItems()).extracting(goal -> goal.title())
				.containsExactly("PMF", "İlk satış", "Yatırım sunumu");
		assertThat(result.progressPercent()).isEqualTo(33);
		assertThat(result.programId()).isNull();
	}

	@Test
	void aMentorshipThatDoesNotExistIsReportedAsNotFound() {
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorshipService.findById(MENTORSHIP_ID, null))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(MENTORSHIP_ID.toString());
		assertThatThrownBy(() -> mentorshipService.delete(MENTORSHIP_ID, MENTOR_ID))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(mentorshipRepository, never()).delete(any());
	}

	@Test
	void findById_showsANamedCallerOnlyAMentorshipTheyAreIn() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), null);
		AppUser mentor = mentorship.getMentor();
		AppUser outsider = user(OTHER_MENTOR_ID, "baska@tekno.com", "Başka Mentor", UserRole.MENTOR);
		AppUser teammate = user(UUID.fromString("00000000-0000-0000-0000-000000000203"), "ekip@tekno.com", "Ekip",
				UserRole.MENTEE);
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		when(domainLookupService.requireUser(OTHER_MENTOR_ID)).thenReturn(outsider);
		when(domainLookupService.requireUser(teammate.getId())).thenReturn(teammate);
		when(startupAccessService.isTeamOrStaff(any(AppUser.class), eq(ORGANIZATION_ID)))
				.thenAnswer(call -> teammate.equals(call.getArgument(0)));

		assertThat(mentorshipService.findById(MENTORSHIP_ID, MENTOR_ID).id()).isEqualTo(MENTORSHIP_ID);
		assertThat(mentorshipService.findById(MENTORSHIP_ID, teammate.getId()).id()).isEqualTo(MENTORSHIP_ID);
		assertThatThrownBy(() -> mentorshipService.findById(MENTORSHIP_ID, OTHER_MENTOR_ID))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	// --- Creating ---

	@Test
	void create_rejectsDaysPerWeekOutsideOneToSeven() {
		for (int daysPerWeek : List.of(0, 8)) {
			assertThatThrownBy(() -> mentorshipService.create(new CreateMentorshipRequest(MENTOR_ID,
					ORGANIZATION_ID, MentorshipStatus.ACTIVE, LocalDate.of(2026, 8, 10), null, daysPerWeek, null)))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("daysPerWeek must be between 1 and 7");
		}
		verifyNoInteractions(domainLookupService, mentorshipRepository);
	}

	@Test
	void create_withoutStatusEndOrFrequencyStartsAnOpenEndedActiveMentorshipOnceAWeek() {
		AppUser mentor = user(MENTOR_ID, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		AppUser mentee = user(UUID.randomUUID(), "mentee@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser coordinator = user(UUID.randomUUID(), "elif@tekno.com", "Elif Kaya", UserRole.COORDINATOR);
		Organization organization = organization(ORGANIZATION_ID, "Teknokent");
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
		when(mentorshipRepository.save(any(Mentorship.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(ORGANIZATION_ID))
				.thenReturn(List.of(mentee, coordinator));

		var result = mentorshipService.create(new CreateMentorshipRequest(MENTOR_ID, ORGANIZATION_ID, null,
				LocalDate.of(2026, 8, 10), null, null, "  Ürün stratejisi  "));

		assertThat(result.status()).isEqualTo(MentorshipStatus.ACTIVE);
		assertThat(result.daysPerWeek()).isEqualTo(1);
		assertThat(result.endDate()).isNull();
		assertThat(result.goals()).isEqualTo("Ürün stratejisi");
		assertThat(result.programId()).isNull();
		verify(notificationService).create(mentee,
				Texts.get(mentee, "notification.regularMentorshipStarted.title"),
				Texts.get(mentee, "notification.regularMentorshipStarted.body", "Dr. Kemal Arslan", "Teknokent",
						Texts.get(mentee, "notification.regularMentorshipStarted.noEnd")),
				"/mentorships?tab=regular");
		verify(notificationService, never()).create(eq(coordinator), any(), any(), any());
	}

	@Test
	void create_recordsTheStartupsProgramAndTellsTheMenteesWhenItEnds() {
		AppUser mentor = user(MENTOR_ID, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		AppUser mentee = user(UUID.randomUUID(), "mentee@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		Organization organization = organization(ORGANIZATION_ID, "Teknokent");
		Program program = new Program();
		program.setId(UUID.fromString("00000000-0000-0000-0000-0000000005a1"));
		program.setName("METU BİGG 2026");
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(organization);
		when(menteeProgramService.programOf(organization)).thenReturn(Optional.of(program.getId()));
		when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
		when(mentorshipRepository.save(any(Mentorship.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(ORGANIZATION_ID)).thenReturn(List.of(mentee));

		var result = mentorshipService.create(new CreateMentorshipRequest(MENTOR_ID, ORGANIZATION_ID,
				MentorshipStatus.PLANNED, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10), 7, null));

		assertThat(result.status()).isEqualTo(MentorshipStatus.PLANNED);
		assertThat(result.daysPerWeek()).isEqualTo(7);
		assertThat(result.programId()).isEqualTo(program.getId());
		assertThat(result.programName()).isEqualTo("METU BİGG 2026");
		verify(notificationService).create(mentee,
				Texts.get(mentee, "notification.regularMentorshipStarted.title"),
				Texts.get(mentee, "notification.regularMentorshipStarted.body", "Dr. Kemal Arslan", "Teknokent",
						"2026-08-10"),
				"/mentorships?tab=regular");
	}

	// --- Changing and deleting: only the assigned mentor ---

	@Test
	void onlyTheAssignedMentorCanChangeAMentorship() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));
		UpdateMentorshipRequest change = new UpdateMentorshipRequest(LocalDate.of(2026, 2, 1), null, 5);

		for (UUID actorId : new UUID[] { OTHER_MENTOR_ID, null }) {
			assertThatThrownBy(() -> mentorshipService.update(MENTORSHIP_ID, actorId, change))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("Only the assigned mentor");
			assertThatThrownBy(() -> mentorshipService.delete(MENTORSHIP_ID, actorId))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("Only the assigned mentor");
			assertThatThrownBy(() -> mentorshipService.addGoal(MENTORSHIP_ID, actorId,
					new CreateMentorshipGoalRequest("PMF")))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("Only the assigned mentor");
			assertThatThrownBy(() -> mentorshipService.updateGoal(MENTORSHIP_ID, UUID.randomUUID(), actorId,
					new UpdateMentorshipGoalRequest(true)))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("Only the assigned mentor");
			assertThatThrownBy(() -> mentorshipService.upsertWeekCheck(MENTORSHIP_ID, actorId,
					new UpsertMentorshipWeekCheckRequest(LocalDate.of(2026, 3, 2), true)))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("Only the assigned mentor");
		}
		assertThat(mentorship.getStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(mentorship.getDaysPerWeek()).isEqualTo(3);
		verify(mentorshipRepository, never()).save(any());
		verify(mentorshipRepository, never()).delete(any());
		verifyNoInteractions(mentorshipGoalRepository, mentorshipWeekCheckRepository);
	}

	@Test
	void update_rejectsDaysPerWeekOutsideOneToSeven() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));

		for (int daysPerWeek : List.of(0, 8)) {
			assertThatThrownBy(() -> mentorshipService.update(MENTORSHIP_ID, MENTOR_ID,
					new UpdateMentorshipRequest(LocalDate.of(2026, 2, 1), null, daysPerWeek)))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("daysPerWeek must be between 1 and 7");
		}
		assertThat(mentorship.getStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
		verify(mentorshipRepository, never()).save(any());
	}

	@Test
	void update_keepsTheFrequencyWhenNoneIsSentAndCanMakeTheMentorshipOpenEnded() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));
		when(mentorshipRepository.save(mentorship)).thenReturn(mentorship);

		var result = mentorshipService.update(MENTORSHIP_ID, MENTOR_ID,
				new UpdateMentorshipRequest(LocalDate.of(2026, 2, 1), null, null));

		assertThat(result.startDate()).isEqualTo(LocalDate.of(2026, 2, 1));
		assertThat(result.endDate()).isNull();
		assertThat(result.daysPerWeek()).isEqualTo(3);
	}

	// --- Goals ---

	@Test
	void addGoal_putsTheNewGoalAfterTheExistingOnes() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), null);
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));
		when(mentorshipGoalRepository.countByMentorshipId(MENTORSHIP_ID)).thenReturn(2L);
		when(mentorshipGoalRepository.save(any(MentorshipGoal.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var goal = mentorshipService.addGoal(MENTORSHIP_ID, MENTOR_ID,
				new CreateMentorshipGoalRequest("  Yatırım sunumu  "));

		assertThat(goal.title()).isEqualTo("Yatırım sunumu");
		assertThat(goal.sortOrder()).isEqualTo(2);
		assertThat(goal.done()).isFalse();
	}

	@Test
	void addGoal_rejectsATitleThatIsOnlySpaces() {
		when(mentorshipRepository.findById(MENTORSHIP_ID))
				.thenReturn(Optional.of(mentorshipBetween(LocalDate.of(2026, 1, 1), null)));

		assertThatThrownBy(() -> mentorshipService.addGoal(MENTORSHIP_ID, MENTOR_ID,
				new CreateMentorshipGoalRequest("   ")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("title is required");
		verify(mentorshipGoalRepository, never()).save(any());
	}

	@Test
	void updateGoal_refusesAGoalThatBelongsToAnotherMentorship() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), null);
		Mentorship another = mentorship(UUID.fromString("00000000-0000-0000-0000-000000000402"), MENTOR_ID,
				ORGANIZATION_ID, null, MentorshipStatus.ACTIVE, LocalDate.of(2026, 1, 1), null, 1);
		UUID goalId = UUID.fromString("00000000-0000-0000-0000-000000000601");
		MentorshipGoal foreign = MentorshipGoal.create(another, "PMF", 0);
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));
		when(mentorshipGoalRepository.findById(goalId)).thenReturn(Optional.of(foreign));

		assertThatThrownBy(() -> mentorshipService.updateGoal(MENTORSHIP_ID, goalId, MENTOR_ID,
				new UpdateMentorshipGoalRequest(true)))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Mentorship goal not found");
		assertThat(foreign.isDone()).isFalse();
		verify(mentorshipGoalRepository, never()).save(any());
	}

	@Test
	void updateGoal_reportsAGoalThatDoesNotExist() {
		UUID goalId = UUID.fromString("00000000-0000-0000-0000-000000000601");
		when(mentorshipRepository.findById(MENTORSHIP_ID))
				.thenReturn(Optional.of(mentorshipBetween(LocalDate.of(2026, 1, 1), null)));
		when(mentorshipGoalRepository.findById(goalId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorshipService.updateGoal(MENTORSHIP_ID, goalId, MENTOR_ID,
				new UpdateMentorshipGoalRequest(true)))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(goalId.toString());
	}

	@Test
	void updateGoal_canReopenAGoalThatWasDone() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 1, 1), null);
		UUID goalId = UUID.fromString("00000000-0000-0000-0000-000000000601");
		MentorshipGoal goal = MentorshipGoal.create(mentorship, "PMF", 0);
		goal.setDone(true);
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));
		when(mentorshipGoalRepository.findById(goalId)).thenReturn(Optional.of(goal));
		when(mentorshipGoalRepository.save(goal)).thenReturn(goal);

		var reopened = mentorshipService.updateGoal(MENTORSHIP_ID, goalId, MENTOR_ID,
				new UpdateMentorshipGoalRequest(false));

		assertThat(reopened.done()).isFalse();
		assertThat(goal.isDone()).isFalse();
	}

	// --- Weekly ticks ---

	@Test
	void listWeekChecks_requiresAMentor() {
		assertThatThrownBy(() -> mentorshipService.listWeekChecks(null, LocalDate.of(2026, 9, 7)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("mentorId is required");
		verifyNoInteractions(mentorshipRepository);
	}

	@Test
	void listWeekChecks_showsEveryMentorshipOfThatWeekWithItsTick() {
		LocalDate monday = LocalDate.of(2026, 9, 7);
		Mentorship ticked = mentorshipBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 20));
		Mentorship unticked = mentorship(UUID.fromString("00000000-0000-0000-0000-000000000402"), MENTOR_ID,
				ORGANIZATION_ID, null, MentorshipStatus.ACTIVE, LocalDate.of(2026, 9, 9), null, 1);
		Mentorship tickTakenBack = mentorship(UUID.fromString("00000000-0000-0000-0000-000000000403"), MENTOR_ID,
				ORGANIZATION_ID, null, MentorshipStatus.ACTIVE, LocalDate.of(2026, 9, 1), null, 2);
		// Any day of the week is taken as its Monday.
		when(mentorshipRepository.findOverlappingWeekForMentor(MENTOR_ID, MentorshipStatus.ACTIVE, monday,
				monday.plusDays(6))).thenReturn(List.of(ticked, unticked, tickTakenBack));
		when(mentorshipWeekCheckRepository.findByMentorshipIdInAndWeekStart(
				List.of(ticked.getId(), unticked.getId(), tickTakenBack.getId()), monday))
				.thenReturn(List.of(MentorshipWeekCheck.create(ticked, monday, true),
						MentorshipWeekCheck.create(tickTakenBack, monday, false)));

		var checks = mentorshipService.listWeekChecks(MENTOR_ID, LocalDate.of(2026, 9, 10));

		assertThat(checks).extracting(check -> check.mentorshipId())
				.containsExactly(ticked.getId(), unticked.getId(), tickTakenBack.getId());
		assertThat(checks).extracting(check -> check.completed()).containsExactly(true, false, false);
		assertThat(checks).allSatisfy(check -> assertThat(check.weekStart()).isEqualTo(monday));
		assertThat(checks.getFirst().organizationName()).isEqualTo("Teknokent");
		assertThat(checks.getFirst().daysPerWeek()).isEqualTo(3);
		assertThat(checks.getFirst().endDate()).isEqualTo(LocalDate.of(2026, 12, 20));
		assertThat(checks.get(1).startDate()).isEqualTo(LocalDate.of(2026, 9, 9));
	}

	@Test
	void listWeekChecks_withoutADateShowsTheCurrentWeek() {
		LocalDate before = LocalDate.now(MeetingSlotTimes.APP_ZONE);

		mentorshipService.listWeekChecks(MENTOR_ID, null);

		ArgumentCaptor<LocalDate> weekStart = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> weekEnd = ArgumentCaptor.forClass(LocalDate.class);
		verify(mentorshipRepository).findOverlappingWeekForMentor(eq(MENTOR_ID), eq(MentorshipStatus.ACTIVE),
				weekStart.capture(), weekEnd.capture());
		assertThat(weekStart.getValue().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(weekEnd.getValue()).isEqualTo(weekStart.getValue().plusDays(6));
		assertThat(weekStart.getValue()).isBetween(before.minusDays(6),
				LocalDate.now(MeetingSlotTimes.APP_ZONE));
	}

	@Test
	void aWeekRunsFromMondayToSunday() {
		assertThat(MentorshipService.mondayOf(LocalDate.of(2026, 9, 7))).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(MentorshipService.mondayOf(LocalDate.of(2026, 9, 13))).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(MentorshipService.mondayOf(LocalDate.of(2026, 9, 14))).isEqualTo(LocalDate.of(2026, 9, 14));
	}

	@Test
	void upsertWeekCheck_refusesAWeekTheMentorshipDoesNotReach() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 15));
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));

		for (LocalDate outside : List.of(LocalDate.of(2026, 2, 23), LocalDate.of(2026, 3, 16))) {
			assertThatThrownBy(() -> mentorshipService.upsertWeekCheck(MENTORSHIP_ID, MENTOR_ID,
					new UpsertMentorshipWeekCheckRequest(outside, true)))
					.as("week of %s", outside)
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("not active in the selected week");
		}
		verifyNoInteractions(mentorshipWeekCheckRepository);
	}

	@Test
	void upsertWeekCheck_acceptsTheWeeksTheMentorshipStartsAndEndsIn() {
		Mentorship mentorship = mentorshipBetween(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 16));
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(mentorship));
		when(mentorshipWeekCheckRepository.save(any(MentorshipWeekCheck.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		for (LocalDate edge : List.of(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 16))) {
			var check = mentorshipService.upsertWeekCheck(MENTORSHIP_ID, MENTOR_ID,
					new UpsertMentorshipWeekCheckRequest(edge, true));

			assertThat(check.weekStart()).isEqualTo(edge);
			assertThat(check.completed()).isTrue();
		}
	}

	@Test
	void upsertWeekCheck_changesTheWeeksExistingTickWhateverDayOfTheWeekIsSent() {
		Mentorship openEnded = mentorshipBetween(LocalDate.of(2026, 1, 1), null);
		LocalDate monday = LocalDate.of(2026, 9, 7);
		MentorshipWeekCheck existing = MentorshipWeekCheck.create(openEnded, monday, true);
		when(mentorshipRepository.findById(MENTORSHIP_ID)).thenReturn(Optional.of(openEnded));
		when(mentorshipWeekCheckRepository.findByMentorshipIdAndWeekStart(MENTORSHIP_ID, monday))
				.thenReturn(Optional.of(existing));
		when(mentorshipWeekCheckRepository.save(existing)).thenReturn(existing);

		var check = mentorshipService.upsertWeekCheck(MENTORSHIP_ID, MENTOR_ID,
				new UpsertMentorshipWeekCheckRequest(LocalDate.of(2026, 9, 10), false));

		assertThat(check.weekStart()).isEqualTo(monday);
		assertThat(check.completed()).isFalse();
		assertThat(existing.isCompleted()).isFalse();
		assertThat(check.organizationName()).isEqualTo("Teknokent");
		assertThat(check.endDate()).isNull();
	}

	private static Mentorship mentorship(
			UUID id,
			UUID mentorId,
			UUID organizationId,
			String goals,
			MentorshipStatus status,
			LocalDate startDate,
			LocalDate endDate,
			int daysPerWeek) {
		Mentorship mentorship = new Mentorship();
		mentorship.setId(id);
		mentorship.setMentor(user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR));
		mentorship.setOrganization(organization(organizationId, "Teknokent"));
		mentorship.setStatus(status);
		mentorship.setStartDate(startDate);
		mentorship.setEndDate(endDate);
		mentorship.setDaysPerWeek(daysPerWeek);
		mentorship.setGoals(goals);
		return mentorship;
	}

	private static AppUser user(UUID id, String email, String fullName, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setEmail(email);
		user.setFullName(fullName);
		user.setRole(role);
		return user;
	}

	private static Organization organization(UUID id, String name) {
		Organization organization = new Organization();
		organization.setId(id);
		organization.setName(name);
		return organization;
	}
}
