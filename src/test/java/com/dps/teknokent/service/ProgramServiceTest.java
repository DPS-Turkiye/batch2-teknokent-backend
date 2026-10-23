package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class ProgramServiceTest {

	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private NotificationService notificationService;
	@InjectMocks
	private ProgramService programService;

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID MENTEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
	private static final UUID OTHER_PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

	@Test
	void createPersistsAProgramForTheCoordinator() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.save(any(Program.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		var result = programService.create(COORDINATOR_ID, "  Seed 2026  ");

		assertThat(result.name()).isEqualTo("Seed 2026");
		assertThat(result.coordinatorId()).isEqualTo(COORDINATOR_ID);
		ArgumentCaptor<Program> captor = ArgumentCaptor.forClass(Program.class);
		verify(programRepository).save(captor.capture());
		assertThat(captor.getValue().getCreatedBy()).isEqualTo(COORDINATOR_ID);
		assertThat(captor.getValue().getName()).isEqualTo("Seed 2026");
	}

	@Test
	void createRejectsAMentor() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());

		assertThatThrownBy(() -> programService.create(MENTOR_ID, "Seed"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("coordinator");
	}

	@Test
	void setStatusMarksTheCoordinatorsProgramCompleted() {
		Program program = program();
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		when(programRepository.save(program)).thenReturn(program);
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		var result = programService.setStatus(COORDINATOR_ID, PROGRAM_ID, ProgramStatus.COMPLETED);

		assertThat(program.getStatus()).isEqualTo(ProgramStatus.COMPLETED);
		assertThat(result.status()).isEqualTo(ProgramStatus.COMPLETED);
	}

	@Test
	void setStatusRefusesAnotherCoordinatorsProgram() {
		AppUser other = coordinator();
		other.setId(UUID.randomUUID());
		when(domainLookupService.requireUser(other.getId())).thenReturn(other);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));

		assertThatThrownBy(() -> programService.setStatus(other.getId(), PROGRAM_ID, ProgramStatus.COMPLETED))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void setStatusRefusesAMentor() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());

		assertThatThrownBy(() -> programService.setStatus(MENTOR_ID, PROGRAM_ID, ProgramStatus.COMPLETED))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void inviteMentorLeavesTheMembershipPending() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID)).thenReturn(Optional.empty());
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		programService.inviteMentor(COORDINATOR_ID, PROGRAM_ID, MENTOR_ID);

		ArgumentCaptor<ProgramMentor> captor = ArgumentCaptor.forClass(ProgramMentor.class);
		verify(programMentorRepository).save(captor.capture());
		assertThat(captor.getValue().getMentorId()).isEqualTo(MENTOR_ID);
		assertThat(captor.getValue().getStatus()).isEqualTo(ProgramMentorStatus.PENDING);
		assertThat(captor.getValue().getInvitedBy()).isEqualTo(ProgramMentorInvitedBy.COORDINATOR);
	}

	@Test
	void decideInvitationAcceptsACoordinatorInvite() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		ProgramMentor pending = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(pending));
		when(programMentorRepository.save(any(ProgramMentor.class))).thenAnswer(invocation -> invocation.getArgument(0));

		var result = programService.decideInvitation(MENTOR_ID, PROGRAM_ID, true);

		assertThat(result.programId()).isEqualTo(PROGRAM_ID);
		assertThat(pending.getStatus()).isEqualTo(ProgramMentorStatus.ACCEPTED);
		assertThat(pending.getDecidedAt()).isNotNull();
	}

	@Test
	void inviteMentorRejectsADuplicatePendingInvite() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR)));

		assertThatThrownBy(() -> programService.inviteMentor(COORDINATOR_ID, PROGRAM_ID, MENTOR_ID))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void addStartupAttachesTheOrganization() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		Organization organization = new Organization();
		organization.setId(ORG_ID);
		organization.setName("NovaMed AI");
		when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.empty());
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		var result = programService.addStartup(COORDINATOR_ID, PROGRAM_ID, ORG_ID);

		assertThat(result.id()).isEqualTo(PROGRAM_ID);
		verify(programStartupRepository).save(any());
	}

	@Test
	void listGivesEachProgramItsOwnStartupsAndMentorsFromOneBatch() {
		Program other = program();
		other.setId(OTHER_PROGRAM_ID);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID))
				.thenReturn(List.of(program(), other));
		when(programStartupRepository.findByProgramIdInOrderByCreatedAtAsc(List.of(PROGRAM_ID, OTHER_PROGRAM_ID)))
				.thenReturn(List.of(assignment(PROGRAM_ID)));
		when(programMentorRepository.findByProgramIdInOrderByCreatedAtAsc(List.of(PROGRAM_ID, OTHER_PROGRAM_ID)))
				.thenReturn(List.of(membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR)));
		when(organizationRepository.findAllById(any())).thenReturn(List.of(mentee(null).getOrganization()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator(), mentor()));

		var result = programService.list(COORDINATOR_ID);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).coordinatorName()).isEqualTo("Demo Coordinator");
		assertThat(result.get(0).startups()).singleElement()
				.satisfies(startup -> assertThat(startup.name()).isEqualTo("NovaMed AI"));
		assertThat(result.get(0).mentors()).singleElement()
				.satisfies(mentor -> assertThat(mentor.fullName()).isEqualTo("Ece Demir"));
		assertThat(result.get(1).startups()).isEmpty();
		assertThat(result.get(1).mentors()).isEmpty();
	}

	@Test
	void listAnswersNotFoundWhenAStartupsOrganizationIsGone() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID)).thenReturn(List.of(program()));
		when(programStartupRepository.findByProgramIdInOrderByCreatedAtAsc(any()))
				.thenReturn(List.of(assignment(PROGRAM_ID)));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		assertThatThrownBy(() -> programService.list(COORDINATOR_ID))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("Organization not found: " + ORG_ID);
	}

	@Test
	void listForOrganizationReturnsOnlyTheStartupsOwnAssignments() {
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(mentee(null));
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID))
				.thenReturn(List.of(assignment(PROGRAM_ID)));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));

		var result = programService.listForOrganization(MENTEE_ID);

		assertThat(result.programs()).singleElement()
				.satisfies(entry -> {
					assertThat(entry.id()).isEqualTo(PROGRAM_ID);
					assertThat(entry.name()).isEqualTo("Seed 2026");
					assertThat(entry.coordinatorName()).isEqualTo("Demo Coordinator");
				});
		assertThat(result.activeProgramId()).isNull();
	}

	@Test
	void listForOrganizationReportsTheActiveChoice() {
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(mentee(PROGRAM_ID));
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID))
				.thenReturn(List.of(assignment(PROGRAM_ID)));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));

		assertThat(programService.listForOrganization(MENTEE_ID).activeProgramId()).isEqualTo(PROGRAM_ID);
	}

	@Test
	void listForOrganizationRejectsAUserWithNoOrganization() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());

		assertThatThrownBy(() -> programService.listForOrganization(MENTOR_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("startup member");
	}

	@Test
	void setActiveProgramStoresAnAssignedProgram() {
		AppUser actor = mentee(null);
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(actor);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.of(assignment(PROGRAM_ID)));
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID))
				.thenReturn(List.of(assignment(PROGRAM_ID)));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));

		var result = programService.setActiveProgram(MENTEE_ID, PROGRAM_ID);

		assertThat(actor.getOrganization().getActiveProgramId()).isEqualTo(PROGRAM_ID);
		assertThat(result.activeProgramId()).isEqualTo(PROGRAM_ID);
		verify(organizationRepository).save(actor.getOrganization());
	}

	@Test
	void setActiveProgramRefusesAProgramTheStartupWasNeverAddedTo() {
		AppUser actor = mentee(null);
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(actor);
		when(programStartupRepository.findByProgramIdAndOrganizationId(OTHER_PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> programService.setActiveProgram(MENTEE_ID, OTHER_PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("has not been added to this program");
		assertThat(actor.getOrganization().getActiveProgramId()).isNull();
	}

	@Test
	void setActiveProgramClearsTheChoiceOnNull() {
		AppUser actor = mentee(PROGRAM_ID);
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(actor);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of());

		var result = programService.setActiveProgram(MENTEE_ID, null);

		assertThat(actor.getOrganization().getActiveProgramId()).isNull();
		assertThat(result.activeProgramId()).isNull();
		assertThat(result.programs()).isEmpty();
	}

	// --- Who may open a program, and the coordinator's answer to a mentor asking to join ---

	@Test
	void findDetailRefusesAMentorWhoHasNotBeenAccepted() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR)));

		assertThatThrownBy(() -> programService.findDetail(MENTOR_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a member");
	}

	@Test
	void findDetailRefusesAStartupThatWasNotAddedToTheProgram() {
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(mentee(null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> programService.findDetail(MENTEE_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a member");
	}

	@Test
	void findDetailRefusesACoordinatorWhoDoesNotRunTheProgram() {
		UUID otherCoordinatorId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
		AppUser other = coordinator();
		other.setId(otherCoordinatorId);
		when(domainLookupService.requireUser(otherCoordinatorId)).thenReturn(other);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(PROGRAM_ID, otherCoordinatorId))
				.thenReturn(false);

		assertThatThrownBy(() -> programService.findDetail(otherCoordinatorId, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a member");
	}

	@Test
	void requestToJoinLeavesTheRequestPendingAndTellsTheCoordinator() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID)).thenReturn(Optional.empty());
		ArgumentCaptor<ProgramMentor> saved = ArgumentCaptor.forClass(ProgramMentor.class);

		programService.requestToJoin(MENTOR_ID, PROGRAM_ID);

		verify(programMentorRepository).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(ProgramMentorStatus.PENDING);
		assertThat(saved.getValue().getInvitedBy()).isEqualTo(ProgramMentorInvitedBy.MENTOR);
		verify(notificationService).create(argThat(user -> COORDINATOR_ID.equals(user.getId())), any(), any(),
				eq("/programs"));
	}

	@Test
	void requestToJoinRefusesAMentorAlreadyInTheProgram() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(membership(ProgramMentorStatus.ACCEPTED, ProgramMentorInvitedBy.MENTOR)));

		assertThatThrownBy(() -> programService.requestToJoin(MENTOR_ID, PROGRAM_ID))
				.isInstanceOf(ConflictException.class);
		verify(notificationService, never()).create(any(), any(), any(), any());
	}

	@Test
	void decideMentorRequestAcceptsAMentorsRequestAndTellsThem() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		ProgramMentor request = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(request));

		programService.decideMentorRequest(COORDINATOR_ID, PROGRAM_ID, MENTOR_ID, true);

		assertThat(request.getStatus()).isEqualTo(ProgramMentorStatus.ACCEPTED);
		assertThat(request.getDecidedAt()).isNotNull();
		verify(notificationService).create(argThat(user -> MENTOR_ID.equals(user.getId())), any(), any(),
				eq("/mentor-dashboard"));
	}

	// A coordinator's own invitation is the mentor's to answer, not the coordinator's.
	@Test
	void decideMentorRequestRefusesToDecideACoordinatorsInvitation() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		ProgramMentor invitation = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(invitation));

		assertThatThrownBy(() -> programService.decideMentorRequest(COORDINATOR_ID, PROGRAM_ID, MENTOR_ID, true))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("cannot be decided");
		assertThat(invitation.getStatus()).isEqualTo(ProgramMentorStatus.PENDING);
	}

	@Test
	void decideMentorRequestRefusesAnotherCoordinator() {
		UUID otherCoordinatorId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
		AppUser other = coordinator();
		other.setId(otherCoordinatorId);
		when(domainLookupService.requireUser(otherCoordinatorId)).thenReturn(other);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(PROGRAM_ID, otherCoordinatorId))
				.thenReturn(false);

		assertThatThrownBy(() -> programService.decideMentorRequest(otherCoordinatorId, PROGRAM_ID, MENTOR_ID, true))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the program coordinator");
	}

	private static AppUser mentee(UUID activeProgramId) {
		Organization organization = new Organization();
		organization.setId(ORG_ID);
		organization.setName("NovaMed AI");
		organization.setActiveProgramId(activeProgramId);
		AppUser user = new AppUser();
		user.setId(MENTEE_ID);
		user.setEmail("founder@novamed.demo");
		user.setFullName("Ayşe Kaya");
		user.setRole(UserRole.MENTEE);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setOrganization(organization);
		return user;
	}

	private static com.dps.teknokent.entity.ProgramStartup assignment(UUID programId) {
		var row = new com.dps.teknokent.entity.ProgramStartup();
		row.setId(UUID.randomUUID());
		row.setProgramId(programId);
		row.setOrganizationId(ORG_ID);
		row.setCreatedAt(Instant.parse("2026-09-01T08:00:00Z"));
		return row;
	}

	private static AppUser coordinator() {
		AppUser user = new AppUser();
		user.setId(COORDINATOR_ID);
		user.setEmail("coordinator@teknokent.demo");
		user.setFullName("Demo Coordinator");
		user.setRole(UserRole.COORDINATOR);
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}

	private static AppUser mentor() {
		AppUser user = new AppUser();
		user.setId(MENTOR_ID);
		user.setEmail("ece.demir@teknokent.demo");
		user.setFullName("Ece Demir");
		user.setRole(UserRole.MENTOR);
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("Seed 2026");
		program.setCreatedBy(COORDINATOR_ID);
		program.setCreatedAt(Instant.parse("2026-09-01T08:00:00Z"));
		return program;
	}

	private static ProgramMentor membership(ProgramMentorStatus status, ProgramMentorInvitedBy invitedBy) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(PROGRAM_ID);
		membership.setMentorId(MENTOR_ID);
		membership.setStatus(status);
		membership.setInvitedBy(invitedBy);
		membership.setCreatedAt(Instant.parse("2026-09-01T09:00:00Z"));
		return membership;
	}
}
