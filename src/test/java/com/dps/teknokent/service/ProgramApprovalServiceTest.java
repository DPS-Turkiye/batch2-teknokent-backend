package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;

@ExtendWith(MockitoExtension.class)
class ProgramApprovalServiceTest {

	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID OTHER_PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000811");
	private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");

	@Mock
	private CoordinatorProgramService coordinatorProgramService;
	@Mock
	private AppUserService appUserService;
	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private NotificationService notificationService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private TeamChangeRequestRepository teamChangeRequestRepository;
	@Mock
	private TeamChangeService teamChangeService;

	@InjectMocks
	private ProgramApprovalService service;

	@Test
	void approvals_listsThePendingPeopleAndStartupsOfTheProgram() {
		AppUser person = mentee(PROGRAM_ID);
		Organization startup = startup(UserAccountStatus.PENDING);
		when(appUserRepository.findByProgramIdAndRoleAndStatusOrderByCreatedAtAsc(PROGRAM_ID, UserRole.MENTEE,
				UserAccountStatus.PENDING)).thenReturn(List.of(person));
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID))
				.thenReturn(List.of(membership()));
		when(organizationRepository.findByIdInAndApprovalStatusOrderByCreatedAtAsc(Set.of(ORGANIZATION_ID),
				UserAccountStatus.PENDING)).thenReturn(List.of(startup));

		var result = service.approvals(ACTOR_ID, PROGRAM_ID);

		verify(coordinatorProgramService).requireManagedProgram(ACTOR_ID, PROGRAM_ID);
		assertThat(result.people()).extracting(row -> row.fullName()).containsExactly("Zeynep Kaya");
		assertThat(result.people().get(0).organizationName()).isNull();
		assertThat(result.startups()).extracting(row -> row.founderName()).containsExactly("Mehmet Can");
	}

	@Test
	void decidePerson_approvesAnApplicantOfTheProgram() {
		AppUser person = mentee(PROGRAM_ID);
		when(domainLookupService.requireUser(USER_ID)).thenReturn(person);

		service.decidePerson(ACTOR_ID, PROGRAM_ID, USER_ID, true);

		verify(appUserService).decide(person, UserAccountStatus.APPROVED);
	}

	@Test
	void decidePerson_refusesSomeoneWhoChoseAnotherProgram() {
		when(domainLookupService.requireUser(USER_ID)).thenReturn(mentee(OTHER_PROGRAM_ID));

		assertThatThrownBy(() -> service.decidePerson(ACTOR_ID, PROGRAM_ID, USER_ID, true))
				.isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(appUserService);
	}

	@Test
	void decideStartup_rejectedStartupStaysAndTheFounderIsTold() {
		Organization startup = startup(UserAccountStatus.PENDING);
		when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(startup);
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORGANIZATION_ID))
				.thenReturn(Optional.of(membership()));

		service.decideStartup(ACTOR_ID, PROGRAM_ID, ORGANIZATION_ID, false);

		assertThat(startup.getApprovalStatus()).isEqualTo(UserAccountStatus.REJECTED);
		verify(organizationRepository).save(startup);
		verify(notificationService).create(eq(startup.getFounder()), any(), any(), eq("/startup"));
	}

	@Test
	void decideStartup_refusesAStartupOfAnotherProgram() {
		when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(startup(UserAccountStatus.PENDING));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORGANIZATION_ID))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.decideStartup(ACTOR_ID, PROGRAM_ID, ORGANIZATION_ID, true))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(organizationRepository, never()).save(any());
	}

	@Test
	void decideStartup_refusesAStartupThatIsNotWaiting() {
		when(domainLookupService.requireOrganization(ORGANIZATION_ID))
				.thenReturn(startup(UserAccountStatus.APPROVED));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORGANIZATION_ID))
				.thenReturn(Optional.of(membership()));

		assertThatThrownBy(() -> service.decideStartup(ACTOR_ID, PROGRAM_ID, ORGANIZATION_ID, true))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(notificationService);
	}

	@Test
	void decideTeamChange_checksTheProgramThenDecidesAndListsWhatIsLeft() {
		UUID requestId = UUID.randomUUID();
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of());

		var result = service.decideTeamChange(ACTOR_ID, PROGRAM_ID, requestId, true);

		verify(coordinatorProgramService).requireManagedProgram(ACTOR_ID, PROGRAM_ID);
		verify(teamChangeService).decide(ACTOR_ID, PROGRAM_ID, requestId, true);
		assertThat(result.teamChanges()).isEmpty();
	}

	private static AppUser mentee(UUID programId) {
		AppUser person = new AppUser();
		person.setId(USER_ID);
		person.setFullName("Zeynep Kaya");
		person.setEmail("zeynep@kargo.ai");
		person.setRole(UserRole.MENTEE);
		person.setStatus(UserAccountStatus.PENDING);
		person.setProgramId(programId);
		return person;
	}

	private static Organization startup(UserAccountStatus status) {
		AppUser founder = new AppUser();
		founder.setFullName("Mehmet Can");
		founder.setEmail("mehmet@kargo.ai");
		Organization organization = new Organization();
		organization.setId(ORGANIZATION_ID);
		organization.setName("Kargo AI");
		organization.setFounder(founder);
		organization.setApprovalStatus(status);
		return organization;
	}

	private static ProgramStartup membership() {
		ProgramStartup membership = new ProgramStartup();
		membership.setProgramId(PROGRAM_ID);
		membership.setOrganizationId(ORGANIZATION_ID);
		return membership;
	}
}
