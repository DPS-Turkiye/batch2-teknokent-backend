package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class StartupAccessServiceTest {

	private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID PROGRAM_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID COORDINATOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");

	@Mock
	private DomainLookupService domainLookupService;

	@Mock
	private ProgramStartupRepository programStartupRepository;

	@Mock
	private ProgramRepository programRepository;

	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;

	@InjectMocks
	private StartupAccessService service;

	@Test
	void aCoordinatorAddedToTheStartupsProgramCoordinatesIt() {
		startupIn(program(UUID.randomUUID()));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(PROGRAM_ID, COORDINATOR_ID)).thenReturn(true);

		assertThat(service.coordinates(coordinator(), ORG_ID)).isTrue();
	}

	@Test
	void theCoordinatorWhoCreatedTheStartupsProgramCoordinatesIt() {
		startupIn(program(COORDINATOR_ID));

		assertThat(service.coordinates(coordinator(), ORG_ID)).isTrue();
	}

	@Test
	void aCoordinatorOfAnotherProgramDoesNot() {
		startupIn(program(UUID.randomUUID()));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(PROGRAM_ID, COORDINATOR_ID)).thenReturn(false);

		assertThat(service.coordinates(coordinator(), ORG_ID)).isFalse();
	}

	@Test
	void nobodyCoordinatesAStartupInNoProgram() {
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of());

		assertThat(service.coordinates(coordinator(), ORG_ID)).isFalse();
	}

	@Test
	void someoneWithoutTheCoordinatorPermissionNeverCoordinates() {
		AppUser creator = coordinator();
		creator.setRole(UserRole.MENTOR);

		assertThat(service.coordinates(creator, ORG_ID)).isFalse();
		assertThat(service.coordinates(coordinator(), null)).isFalse();
		verify(programRepository, never()).findAllById(any());
	}

	@Test
	void theTeamAnAdminAndTheProgramsCoordinatorPassTheTeamOrStaffCheck() {
		Organization startup = new Organization();
		startup.setId(ORG_ID);
		AppUser member = person(UUID.randomUUID(), UserRole.MENTEE);
		member.setOrganization(startup);
		AppUser admin = person(UUID.randomUUID(), UserRole.ADMIN);
		when(domainLookupService.requireUser(member.getId())).thenReturn(member);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		startupIn(program(COORDINATOR_ID));

		assertThat(service.requireTeamOrStaff(member.getId(), ORG_ID)).isSameAs(member);
		assertThat(service.requireTeamOrStaff(admin.getId(), ORG_ID)).isSameAs(admin);
		assertThat(service.requireTeamOrStaff(COORDINATOR_ID, ORG_ID).getId()).isEqualTo(COORDINATOR_ID);
	}

	@Test
	void anotherStartupsMemberAndAnotherProgramsCoordinatorAreRefused() {
		Organization otherStartup = new Organization();
		otherStartup.setId(UUID.randomUUID());
		AppUser outsider = person(UUID.randomUUID(), UserRole.MENTEE);
		outsider.setOrganization(otherStartup);
		when(domainLookupService.requireUser(outsider.getId())).thenReturn(outsider);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		startupIn(program(UUID.randomUUID()));

		for (UUID actorId : List.of(outsider.getId(), COORDINATOR_ID)) {
			assertThatThrownBy(() -> service.requireTeamOrStaff(actorId, ORG_ID))
					.isInstanceOfSatisfying(ResponseStatusException.class,
							refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		}
	}

	private void startupIn(Program program) {
		ProgramStartup link = new ProgramStartup();
		link.setProgramId(PROGRAM_ID);
		link.setOrganizationId(ORG_ID);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of(link));
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program));
	}

	private static Program program(UUID createdBy) {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setCreatedBy(createdBy);
		return program;
	}

	private static AppUser coordinator() {
		return person(COORDINATOR_ID, UserRole.COORDINATOR);
	}

	private static AppUser person(UUID id, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName("Demo " + role);
		user.setRole(role);
		return user;
	}
}
