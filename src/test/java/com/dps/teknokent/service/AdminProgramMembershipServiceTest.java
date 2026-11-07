package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class AdminProgramMembershipServiceTest {

	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ad");
	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserService appUserService;
	@Mock
	private NotificationService notificationService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramEligibilityService programEligibilityService;

	@InjectMocks
	private AdminProgramMembershipService service;

	@Test
	void assigningAMentorPutsThemInAtOnceApprovesTheAccountAndTellsTheCoordinator() {
		givenAdminAndProgram(ProgramStatus.ACTIVE);
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.PENDING);
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId())).thenReturn(Optional.empty());
		AppUser coordinator = user(COORDINATOR_ID, UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		when(appUserRepository.findAllById(List.of(COORDINATOR_ID))).thenReturn(List.of(coordinator));

		service.assignMentor(ADMIN_ID, PROGRAM_ID, mentor.getId());

		ArgumentCaptor<ProgramMentor> saved = ArgumentCaptor.forClass(ProgramMentor.class);
		verify(programMentorRepository).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(ProgramMentorStatus.ACCEPTED);
		assertThat(saved.getValue().getInvitedBy()).isEqualTo(ProgramMentorInvitedBy.COORDINATOR);
		verify(appUserService).decide(mentor, UserAccountStatus.APPROVED);
		verify(notificationService).create(eq(coordinator), anyString(), anyString(),
				eq("/coordinator/programs/" + PROGRAM_ID));
	}

	@Test
	void aMentorAlreadyInTheProgramIsRefused() {
		givenAdminAndProgram(ProgramStatus.ACTIVE);
		AppUser mentor = user(UUID.randomUUID(), UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);
		ProgramMentor accepted = new ProgramMentor();
		accepted.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId()))
				.thenReturn(Optional.of(accepted));

		assertThatThrownBy(() -> service.assignMentor(ADMIN_ID, PROGRAM_ID, mentor.getId()))
				.isInstanceOf(ConflictException.class);
		verify(programMentorRepository, never()).save(any());
	}

	@Test
	void assigningAStartupJoinsItApprovesItAndMakesItTheActiveProgramWhenItHadNone() {
		givenAdminAndProgram(ProgramStatus.ACTIVE);
		AppUser founder = user(UUID.randomUUID(), UserRole.MENTEE, UserAccountStatus.APPROVED);
		Organization startup = new Organization();
		startup.setId(UUID.randomUUID());
		startup.setName("Flexvision");
		startup.setApprovalStatus(UserAccountStatus.PENDING);
		startup.setFounder(founder);
		when(domainLookupService.requireOrganization(startup.getId())).thenReturn(startup);
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, startup.getId()))
				.thenReturn(Optional.empty());

		service.assignStartup(ADMIN_ID, PROGRAM_ID, startup.getId());

		ArgumentCaptor<ProgramStartup> row = ArgumentCaptor.forClass(ProgramStartup.class);
		verify(programStartupRepository).save(row.capture());
		assertThat(row.getValue().getProgramId()).isEqualTo(PROGRAM_ID);
		assertThat(startup.getActiveProgramId()).isEqualTo(PROGRAM_ID);
		assertThat(startup.getApprovalStatus()).isEqualTo(UserAccountStatus.APPROVED);
		verify(notificationService).create(eq(founder), anyString(), anyString(), eq("/startup"));
		verify(appUserService, never()).decide(any(), any());
	}

	@Test
	void aFinishedProgramTakesNobody() {
		givenAdminAndProgram(ProgramStatus.COMPLETED);

		assertThatThrownBy(() -> service.assignMentor(ADMIN_ID, PROGRAM_ID, UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void onlyAnAdminCanAssign() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, UserAccountStatus.APPROVED));

		assertThatThrownBy(() -> service.assignMentor(COORDINATOR_ID, PROGRAM_ID, UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class);
		verify(programMentorRepository, never()).save(any());
	}

	private void givenAdminAndProgram(ProgramStatus status) {
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN, UserAccountStatus.APPROVED));
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setCreatedBy(COORDINATOR_ID);
		program.setCreatedAt(Instant.now());
		program.setStatus(status);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
	}

	private static AppUser user(UUID id, UserRole role, UserAccountStatus status) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(role);
		user.setStatus(status);
		user.setFullName("Kişi " + role);
		user.setEmail(role.name().toLowerCase() + "@test.co");
		return user;
	}
}
