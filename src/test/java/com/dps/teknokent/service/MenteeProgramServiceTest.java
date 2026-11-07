package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class MenteeProgramServiceTest {

	private static final UUID FIRST_PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID SECOND_PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
	private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");

	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private ProgramRepository programRepository;

	@InjectMocks
	private MenteeProgramService service;

	@Test
	void programOf_isTheStartupsChosenProgram() {
		Organization startup = startup();
		startup.setActiveProgramId(FIRST_PROGRAM);

		assertThat(service.programOf(startup)).contains(FIRST_PROGRAM);
	}

	@Test
	void programOf_fallsBackToTheLastProgramTheStartupJoined() {
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORGANIZATION_ID))
				.thenReturn(List.of(joined(FIRST_PROGRAM, "2026-01-01"), joined(SECOND_PROGRAM, "2026-09-01")));

		assertThat(service.programOf(startup())).contains(SECOND_PROGRAM);
	}

	@Test
	void programOf_isEmptyForAStartupOutsideEveryProgram() {
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORGANIZATION_ID)).thenReturn(List.of());

		assertThat(service.programOf(startup())).isEmpty();
	}

	@Test
	void programOf_aMenteeWithoutAStartupUsesTheProgramTheySignedUpInto() {
		AppUser mentee = user(UserRole.MENTEE);
		mentee.setProgramId(FIRST_PROGRAM);

		assertThat(service.programOf(mentee)).contains(FIRST_PROGRAM);
	}

	@Test
	void reachableMentorIds_areTheAcceptedMentorsOfTheMenteesProgram() {
		AppUser mentee = user(UserRole.MENTEE);
		Organization startup = startup();
		startup.setActiveProgramId(FIRST_PROGRAM);
		mentee.setOrganization(startup);
		UUID accepted = UUID.randomUUID();
		when(appUserRepository.findById(mentee.getId())).thenReturn(Optional.of(mentee));
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(FIRST_PROGRAM)).thenReturn(List.of(
				membership(accepted, ProgramMentorStatus.ACCEPTED),
				membership(UUID.randomUUID(), ProgramMentorStatus.PENDING),
				membership(UUID.randomUUID(), ProgramMentorStatus.REJECTED)));

		assertThat(service.reachableMentorIds(mentee.getId())).contains(java.util.Set.of(accepted));
	}

	@Test
	void reachableMentorIds_limitNobodyButMenteesInAProgram() {
		AppUser coordinator = user(UserRole.COORDINATOR);
		AppUser independent = user(UserRole.MENTEE);
		independent.setOrganization(startup());
		when(appUserRepository.findById(coordinator.getId())).thenReturn(Optional.of(coordinator));
		when(appUserRepository.findById(independent.getId())).thenReturn(Optional.of(independent));
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORGANIZATION_ID)).thenReturn(List.of());

		assertThat(service.reachableMentorIds(coordinator.getId())).isEmpty();
		assertThat(service.reachableMentorIds(independent.getId())).isEmpty();
		assertThat(service.reachableMentorIds(null)).isEmpty();
	}

	@Test
	void inIndividualProgram_followsTheStartupsCurrentProgram() {
		Organization team = startup();
		team.setActiveProgramId(FIRST_PROGRAM);
		Program individual = new Program();
		individual.setIndividualMentees(true);
		when(programRepository.findById(FIRST_PROGRAM)).thenReturn(Optional.of(individual));

		assertThat(service.inIndividualProgram(mentee(team))).isTrue();
	}

	@Test
	void inIndividualProgram_isFalseForAStartupProgramAndForMentors() {
		Organization team = startup();
		team.setActiveProgramId(FIRST_PROGRAM);
		when(programRepository.findById(FIRST_PROGRAM)).thenReturn(Optional.of(new Program()));
		AppUser mentor = mentee(team);
		mentor.setRole(UserRole.MENTOR);

		assertThat(service.inIndividualProgram(mentee(team))).isFalse();
		assertThat(service.inIndividualProgram(mentor)).isFalse();
	}

	private static AppUser mentee(Organization organization) {
		AppUser user = new AppUser();
		user.setRole(UserRole.MENTEE);
		user.setOrganization(organization);
		return user;
	}

	private static Organization startup() {
		Organization startup = new Organization();
		startup.setId(ORGANIZATION_ID);
		return startup;
	}

	private static ProgramStartup joined(UUID programId, String day) {
		ProgramStartup row = new ProgramStartup();
		row.setProgramId(programId);
		row.setOrganizationId(ORGANIZATION_ID);
		row.setCreatedAt(Instant.parse(day + "T09:00:00Z"));
		return row;
	}

	private static ProgramMentor membership(UUID mentorId, ProgramMentorStatus status) {
		ProgramMentor membership = new ProgramMentor();
		membership.setMentorId(mentorId);
		membership.setStatus(status);
		return membership;
	}

	private static AppUser user(UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setRole(role);
		return user;
	}
}
