package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamMembership;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamMembershipRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamMembershipServiceTest {

	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID OTHER_COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

	@Mock
	private TeamMembershipRepository teamMembershipRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private DomainLookupService domainLookupService;

	@InjectMocks
	private TeamMembershipService service;

	private AppUser person;
	private Organization archived;
	private Organization current;

	@BeforeEach
	void setUp() {
		person = user(UUID.randomUUID(), UserRole.MENTEE);
		archived = startup("Eski Takım");
		archived.setArchivedAt(Instant.parse("2026-10-01T09:00:00Z"));
		current = startup("Pazar Lab");
		when(domainLookupService.requireUser(person.getId())).thenReturn(person);
		when(teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(person.getId())).thenReturn(List.of(
				stay(current, Instant.parse("2026-10-05T09:00:00Z"), null, null),
				stay(archived, Instant.parse("2026-09-01T09:00:00Z"), Instant.parse("2026-10-01T09:00:00Z"),
						TeamExitReason.LEFT)));
		ProgramStartup link = new ProgramStartup();
		link.setProgramId(PROGRAM_ID);
		link.setOrganizationId(current.getId());
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(current.getId()))
				.thenReturn(List.of(link));
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setCreatedBy(COORDINATOR_ID);
		when(programRepository.findAllById(Set.of(PROGRAM_ID))).thenReturn(List.of(program));
	}

	@Test
	void history_listsEveryStayIncludingArchivedStartupsForAnAdmin() {
		AppUser admin = user(UUID.randomUUID(), UserRole.ADMIN);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);

		var history = service.history(admin.getId(), person.getId());

		assertThat(history.stays()).extracting(stay -> stay.organizationName())
				.containsExactly("Pazar Lab", "Eski Takım");
		assertThat(history.stays().get(0).leftAt()).isNull();
		assertThat(history.stays().get(1).startupArchived()).isTrue();
		assertThat(history.stays().get(1).exitReason()).isEqualTo(TeamExitReason.LEFT);
	}

	@Test
	void history_isOpenToTheCoordinatorOfThePersonsProgram() {
		AppUser coordinator = user(COORDINATOR_ID, UserRole.COORDINATOR);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator);

		assertThat(service.history(COORDINATOR_ID, person.getId()).stays()).hasSize(2);
	}

	@Test
	void history_isHiddenFromAnotherProgramsCoordinatorAndFromMentees() {
		AppUser stranger = user(OTHER_COORDINATOR_ID, UserRole.COORDINATOR);
		AppUser teammate = user(UUID.randomUUID(), UserRole.MENTEE);
		when(domainLookupService.requireUser(OTHER_COORDINATOR_ID)).thenReturn(stranger);
		when(domainLookupService.requireUser(teammate.getId())).thenReturn(teammate);

		assertThatThrownBy(() -> service.history(OTHER_COORDINATOR_ID, person.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.history(teammate.getId(), person.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.history(person.getId(), person.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void left_closesTheOpenStayWithItsReason() {
		TeamMembership open = stay(current, Instant.parse("2026-10-05T09:00:00Z"), null, null);
		when(teamMembershipRepository.findFirstByUserIdAndOrganizationIdAndLeftAtIsNullOrderByJoinedAtDesc(
				person.getId(), current.getId())).thenReturn(Optional.of(open));

		service.left(person, current, TeamExitReason.REMOVED);

		assertThat(open.getLeftAt()).isNotNull();
		assertThat(open.getExitReason()).isEqualTo(TeamExitReason.REMOVED);
	}

	@Test
	void left_withoutAnOpenStaySavesNothing() {
		when(teamMembershipRepository.findFirstByUserIdAndOrganizationIdAndLeftAtIsNullOrderByJoinedAtDesc(
				person.getId(), current.getId())).thenReturn(Optional.empty());

		service.left(person, current, TeamExitReason.LEFT);

		verify(teamMembershipRepository, never()).save(any());
	}

	private static TeamMembership stay(Organization startup, Instant joinedAt, Instant leftAt,
			TeamExitReason reason) {
		TeamMembership stay = new TeamMembership();
		stay.setOrganization(startup);
		stay.setJoinedAt(joinedAt);
		stay.setLeftAt(leftAt);
		stay.setExitReason(reason);
		return stay;
	}

	private static Organization startup(String name) {
		Organization startup = new Organization();
		startup.setId(UUID.randomUUID());
		startup.setName(name);
		startup.setApprovalStatus(UserAccountStatus.APPROVED);
		return startup;
	}

	private static AppUser user(UUID id, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName("Kişi " + role);
		user.setRole(role);
		return user;
	}
}
