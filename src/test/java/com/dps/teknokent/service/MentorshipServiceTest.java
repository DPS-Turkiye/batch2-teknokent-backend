package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipGoalRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipWeekCheckRepository;
import com.dps.teknokent.repository.ProgramRepository;

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
				LocalDate.now().minusDays(1),
				LocalDate.now().plusDays(10),
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
