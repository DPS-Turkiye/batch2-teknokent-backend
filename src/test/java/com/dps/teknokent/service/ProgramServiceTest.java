package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.Edition;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramInvitationResponse;
import com.dps.teknokent.dto.responses.ProgramMemberSummaryResponse;
import com.dps.teknokent.dto.responses.ProgramNameResponse;
import com.dps.teknokent.dto.responses.ProgramResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.Texts;

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
	@Mock
	private ProgramEligibilityService programEligibilityService;
	@Spy
	private EditionService editionService = new EditionService("TEKNOKENT");

	@InjectMocks
	private ProgramService programService;

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID MENTEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
	private static final UUID OTHER_PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

	@Test
	void createMakesAnAdminsProgramADraft() {
		AppUser admin = coordinator();
		admin.setRole(UserRole.ADMIN);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(admin);
		when(programRepository.save(any(Program.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(admin));

		var result = programService.create(COORDINATOR_ID, "  Seed 2026  ");

		assertThat(result.name()).isEqualTo("Seed 2026");
		assertThat(result.status()).isEqualTo(ProgramStatus.DRAFT);
		ArgumentCaptor<Program> captor = ArgumentCaptor.forClass(Program.class);
		verify(programRepository).save(captor.capture());
		assertThat(captor.getValue().getCreatedBy()).isEqualTo(COORDINATOR_ID);
		assertThat(captor.getValue().getName()).isEqualTo("Seed 2026");
	}

	@Test
	void createRejectsACoordinatorAndAMentor() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());

		assertThatThrownBy(() -> programService.create(COORDINATOR_ID, "Seed"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> programService.create(MENTOR_ID, "Seed"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(programRepository, never()).save(any());
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
		when(programMentorRepository.save(any(ProgramMentor.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

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
	void anAdminWithCoordinatorPermissionGetsTheFullProgramList() {
		AppUser actor = new AppUser();
		actor.setId(COORDINATOR_ID);
		actor.setRole(UserRole.ADMIN);
		org.springframework.test.util.ReflectionTestUtils.setField(actor, "role", UserRole.COORDINATOR);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(actor);
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

		assertThat(programService.list(COORDINATOR_ID)).isEmpty();
		verify(programRepository).findAllByOrderByCreatedAtDesc();
		verify(programRepository, never()).findByCreatedByOrderByCreatedAtDesc(any());
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
					assertThat(entry.individualMentees()).isFalse();
				});
		assertThat(result.activeProgramId()).isNull();
	}

	@Test
	void listForOrganizationFlagsAnIndividualProgram() {
		Program individual = program();
		individual.setIndividualMentees(true);
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(mentee(PROGRAM_ID));
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID))
				.thenReturn(List.of(assignment(PROGRAM_ID)));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(individual));

		assertThat(programService.listForOrganization(MENTEE_ID).programs()).singleElement()
				.satisfies(entry -> assertThat(entry.individualMentees()).isTrue());
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
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));
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
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));
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

	@Test
	void listHidesDraftProgramsFromAMentor() {
		Program draft = program();
		draft.setId(OTHER_PROGRAM_ID);
		draft.setStatus(ProgramStatus.DRAFT);
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(draft, program()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		var result = programService.list(MENTOR_ID);

		assertThat(result).extracting(ProgramResponse::id).containsExactly(PROGRAM_ID);
	}

	@Test
	void setStatusCannotLaunchADraft() {
		Program draft = program();
		draft.setStatus(ProgramStatus.DRAFT);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(draft));

		assertThatThrownBy(() -> programService.setStatus(COORDINATOR_ID, PROGRAM_ID, ProgramStatus.ACTIVE))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("admin");
		assertThat(draft.getStatus()).isEqualTo(ProgramStatus.DRAFT);
	}

	@Test
	void setStatusCannotTurnAProgramBackIntoADraft() {
		Program program = program();
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));

		assertThatThrownBy(() -> programService.setStatus(COORDINATOR_ID, PROGRAM_ID, ProgramStatus.DRAFT))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(program.getStatus()).isEqualTo(ProgramStatus.ACTIVE);
	}

	@Test
	void createRefusesAStartupProgramInTheOdtuEdition() {
		doReturn(Edition.ODTU).when(editionService).current();
		AppUser admin = coordinator();
		admin.setRole(UserRole.ADMIN);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(admin);

		assertThatThrownBy(() -> programService.create(COORDINATOR_ID, "Seed 2026"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not available");
		verify(programRepository, never()).save(any());
	}

	@Test
	void requestToJoinTreatsAProgramOfTheOtherEditionAsMissing() {
		Program peer = program();
		peer.setProgramType(ProgramType.PEER_MENTORING);
		peer.setStatus(ProgramStatus.ACTIVE);
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(peer));

		assertThatThrownBy(() -> programService.requestToJoin(MENTOR_ID, PROGRAM_ID))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(programMentorRepository, never()).save(any());
	}

	@Test
	void theSignupPickerListsOnlyThisEditionsOpenPrograms() {
		Program startup = program();
		startup.setStatus(ProgramStatus.ACTIVE);
		Program peer = program();
		peer.setId(UUID.randomUUID());
		peer.setName("ODTÜ Akran");
		peer.setProgramType(ProgramType.PEER_MENTORING);
		peer.setStatus(ProgramStatus.ACTIVE);
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(peer, startup));

		assertThat(programService.listNames()).extracting(name -> name.name()).containsExactly("Seed 2026");
		doReturn(Edition.ODTU).when(editionService).current();
		assertThat(programService.listNames()).extracting(name -> name.name()).containsExactly("ODTÜ Akran");
	}

	@Test
	void requestToJoinRefusesADraftProgram() {
		Program draft = program();
		draft.setStatus(ProgramStatus.DRAFT);
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(draft));

		assertThatThrownBy(() -> programService.requestToJoin(MENTOR_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("henüz başlatılmadı");
		verify(programMentorRepository, never()).save(any());
	}

	@Test
	void requestToJoinTellsTheCoordinatorsOfAnAdminCreatedProgramNotTheAdmin() {
		UUID adminId = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
		AppUser admin = coordinator();
		admin.setId(adminId);
		admin.setRole(UserRole.ADMIN);
		Program program = program();
		program.setCreatedBy(adminId);
		ProgramCoordinator row = new ProgramCoordinator();
		row.setId(UUID.randomUUID());
		row.setProgramId(PROGRAM_ID);
		row.setUserId(COORDINATOR_ID);
		row.setAddedBy(adminId);
		row.setCreatedAt(Instant.now());
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		when(programCoordinatorRepository.findByProgramIdIn(any())).thenReturn(List.of(row));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(admin, coordinator()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID)).thenReturn(Optional.empty());

		programService.requestToJoin(MENTOR_ID, PROGRAM_ID);

		verify(notificationService).create(argThat(user -> COORDINATOR_ID.equals(user.getId())), any(), any(),
				eq("/programs"));
		verify(notificationService, never()).create(argThat(user -> adminId.equals(user.getId())), any(), any(),
				any());
	}

	@Test
	void aDraftTakesNoMentorInviteAndNoStartupYet() {
		Program draft = program();
		draft.setStatus(ProgramStatus.DRAFT);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(draft));

		assertThatThrownBy(() -> programService.inviteMentor(COORDINATOR_ID, PROGRAM_ID, MENTOR_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("henüz başlatılmadı");
		assertThatThrownBy(() -> programService.addStartup(COORDINATOR_ID, PROGRAM_ID, ORG_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("henüz başlatılmadı");
		verify(programMentorRepository, never()).save(any());
		verify(programStartupRepository, never()).save(any());
		verify(notificationService, never()).create(any(), any(), any(), any());
	}

	@Test
	void createRequiresAName() {
		AppUser admin = coordinator();
		admin.setRole(UserRole.ADMIN);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(admin);

		assertThatThrownBy(() -> programService.create(COORDINATOR_ID, "   "))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("name is required");
		assertThatThrownBy(() -> programService.create(COORDINATOR_ID, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("name is required");
		verify(programRepository, never()).save(any());
	}

	@Test
	void nothingIsDoneWithoutASignedInUser() {
		assertThatThrownBy(() -> programService.create(null, "Seed 2026"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("signed-in user");
		assertThatThrownBy(() -> programService.findDetail(null, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("signed-in user");
		verifyNoInteractions(domainLookupService, programRepository);
	}

	@Test
	void listRefusesAStartupMember() {
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(mentee(null));

		assertThatThrownBy(() -> programService.list(MENTEE_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("coordinator or mentor");
		verifyNoInteractions(programRepository);
	}

	@Test
	void theSignupPickerLeavesOutDraftsAndCompletedPrograms() {
		Program draft = program();
		draft.setId(UUID.randomUUID());
		draft.setStatus(ProgramStatus.DRAFT);
		Program completed = program();
		completed.setId(UUID.randomUUID());
		completed.setStatus(ProgramStatus.COMPLETED);
		Program open = program();
		open.setIndividualMentees(true);
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(draft, open, completed));

		assertThat(programService.listNames()).containsExactly(new ProgramNameResponse(PROGRAM_ID, "Seed 2026", true));
	}

	@Test
	void aMentorSeesTheCoordinatorInvitesStillWaitingForTheirAnswer() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		ProgramMentor invite = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR);
		when(programMentorRepository.findByMentorIdAndStatusAndInvitedByOrderByCreatedAtDesc(
				MENTOR_ID, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR))
				.thenReturn(List.of(invite));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		assertThat(programService.listInvitations(MENTOR_ID)).containsExactly(new ProgramInvitationResponse(
				PROGRAM_ID, "Seed 2026", COORDINATOR_ID, "Demo Coordinator", invite.getCreatedAt()));
	}

	@Test
	void onlyAMentorSeesAnswersOrAsksForProgramMembership() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());

		assertThatThrownBy(() -> programService.listInvitations(COORDINATOR_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only a mentor");
		assertThatThrownBy(() -> programService.decideInvitation(COORDINATOR_ID, PROGRAM_ID, true))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only a mentor");
		assertThatThrownBy(() -> programService.requestToJoin(COORDINATOR_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only a mentor");
		verifyNoInteractions(programMentorRepository, notificationService);
	}

	@Test
	void addStartupRefusesAStartupAlreadyInTheProgram() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.of(assignment(PROGRAM_ID)));

		assertThatThrownBy(() -> programService.addStartup(COORDINATOR_ID, PROGRAM_ID, ORG_ID))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("already in the program");
		verify(programStartupRepository, never()).save(any());
	}

	@Test
	void addStartupRefusesATeamTheProgramsRulesExclude() {
		Program program = program();
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.empty());
		doThrow(new InvalidRequestException("Not eligible.")).when(programEligibilityService)
				.requireTeam(program, ORG_ID);

		assertThatThrownBy(() -> programService.addStartup(COORDINATOR_ID, PROGRAM_ID, ORG_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Not eligible.");
		verify(programStartupRepository, never()).save(any());
	}

	@Test
	void removeStartupDetachesTheOrganization() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		ProgramStartup row = assignment(PROGRAM_ID);
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.of(row));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		var result = programService.removeStartup(COORDINATOR_ID, PROGRAM_ID, ORG_ID);

		verify(programStartupRepository).delete(row);
		assertThat(result.id()).isEqualTo(PROGRAM_ID);
	}

	@Test
	void removingAStartupThatIsNotInTheProgramIsNotFound() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> programService.removeStartup(COORDINATOR_ID, PROGRAM_ID, ORG_ID))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("not in this program");
		verify(programStartupRepository, never()).delete(any());
	}

	@Test
	void inviteMentorRefusesSomeoneWhoIsNotAnApprovedMentor() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		AppUser awaitingApproval = mentor();
		awaitingApproval.setStatus(UserAccountStatus.PENDING);
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(awaitingApproval);
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(mentee(null));

		assertThatThrownBy(() -> programService.inviteMentor(COORDINATOR_ID, PROGRAM_ID, MENTEE_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a mentor");
		assertThatThrownBy(() -> programService.inviteMentor(COORDINATOR_ID, PROGRAM_ID, MENTOR_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("approved mentor");
		verifyNoInteractions(programMentorRepository, notificationService);
	}

	@Test
	void aMentorWhoseEarlierRequestWasRejectedCanBeInvitedAgain() {
		AppUser mentor = mentor();
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		ProgramMentor rejected = membership(ProgramMentorStatus.REJECTED, ProgramMentorInvitedBy.MENTOR);
		rejected.setDecidedAt(Instant.parse("2026-09-02T09:00:00Z"));
		Instant firstAsked = rejected.getCreatedAt();
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(rejected));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		programService.inviteMentor(COORDINATOR_ID, PROGRAM_ID, MENTOR_ID);

		assertThat(rejected.getStatus()).isEqualTo(ProgramMentorStatus.PENDING);
		assertThat(rejected.getInvitedBy()).isEqualTo(ProgramMentorInvitedBy.COORDINATOR);
		assertThat(rejected.getDecidedAt()).isNull();
		assertThat(rejected.getCreatedAt()).isAfter(firstAsked);
		verify(programMentorRepository).save(rejected);
		verify(notificationService).create(same(mentor), any(),
				eq(Texts.get(mentor, "notification.programInvite.body", "Seed 2026")), eq("/mentor-dashboard"));
	}

	@Test
	void aMentorTheProgramsRulesExcludeIsNotInvited() {
		AppUser mentor = mentor();
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(coordinator());
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		doThrow(new InvalidRequestException("Not eligible.")).when(programEligibilityService)
				.requireMentor(PROGRAM_ID, mentor);

		assertThatThrownBy(() -> programService.inviteMentor(COORDINATOR_ID, PROGRAM_ID, MENTOR_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Not eligible.");
		verifyNoInteractions(programMentorRepository, notificationService);
	}

	@Test
	void decideInvitationRefusesAnAnsweredInviteAMentorsOwnRequestAndAMissingOne() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		ProgramMentor answered = membership(ProgramMentorStatus.ACCEPTED, ProgramMentorInvitedBy.COORDINATOR);
		ProgramMentor ownRequest = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(answered))
				.thenReturn(Optional.of(ownRequest))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> programService.decideInvitation(MENTOR_ID, PROGRAM_ID, false))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("cannot be decided");
		assertThatThrownBy(() -> programService.decideInvitation(MENTOR_ID, PROGRAM_ID, true))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("cannot be decided");
		assertThatThrownBy(() -> programService.decideInvitation(MENTOR_ID, PROGRAM_ID, true))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("invitation not found");
		assertThat(answered.getStatus()).isEqualTo(ProgramMentorStatus.ACCEPTED);
		assertThat(ownRequest.getStatus()).isEqualTo(ProgramMentorStatus.PENDING);
		verify(programMentorRepository, never()).save(any());
		verifyNoInteractions(notificationService);
	}

	@Test
	void decliningAnInvitationRejectsItAndTellsTheCoordinatorWithoutAnEligibilityCheck() {
		AppUser coordinator = coordinator();
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator));
		ProgramMentor pending = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(pending));

		programService.decideInvitation(MENTOR_ID, PROGRAM_ID, false);

		assertThat(pending.getStatus()).isEqualTo(ProgramMentorStatus.REJECTED);
		assertThat(pending.getDecidedAt()).isNotNull();
		verify(programMentorRepository).save(pending);
		verify(notificationService).create(same(coordinator), any(),
				eq(Texts.get(coordinator, "notification.inviteDeclined.body", "Ece Demir")), eq("/programs"));
		verifyNoInteractions(programEligibilityService);
	}

	@Test
	void acceptingAnInvitationTellsTheCoordinatorTheMentorAccepted() {
		AppUser coordinator = coordinator();
		AppUser mentor = mentor();
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID)).thenReturn(
				Optional.of(membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR)));

		programService.decideInvitation(MENTOR_ID, PROGRAM_ID, true);

		verify(programEligibilityService).requireMentor(PROGRAM_ID, mentor);
		verify(notificationService).create(same(coordinator), any(),
				eq(Texts.get(coordinator, "notification.inviteAccepted.body", "Ece Demir")), eq("/programs"));
	}

	@Test
	void anInvitationTheMentorIsNoLongerEligibleForCannotBeAccepted() {
		AppUser mentor = mentor();
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		ProgramMentor pending = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(pending));
		doThrow(new InvalidRequestException("Not eligible.")).when(programEligibilityService)
				.requireMentor(PROGRAM_ID, mentor);

		assertThatThrownBy(() -> programService.decideInvitation(MENTOR_ID, PROGRAM_ID, true))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Not eligible.");
		assertThat(pending.getStatus()).isEqualTo(ProgramMentorStatus.PENDING);
		verify(programMentorRepository, never()).save(any());
		verifyNoInteractions(notificationService);
	}

	@Test
	void decliningAMentorsRequestRejectsItAndTellsTheMentor() {
		AppUser mentor = mentor();
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		ProgramMentor request = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(request));

		programService.settleMentorRequest(program(), MENTOR_ID, false);

		assertThat(request.getStatus()).isEqualTo(ProgramMentorStatus.REJECTED);
		assertThat(request.getDecidedAt()).isNotNull();
		verify(programMentorRepository).save(request);
		verify(notificationService).create(same(mentor), any(),
				eq(Texts.get(mentor, "notification.joinRequestDeclined.body", "Seed 2026")), eq("/mentor-dashboard"));
		verifyNoInteractions(programEligibilityService);
	}

	@Test
	void aMentorRequestThatIsMissingOrAlreadyAnsweredCannotBeSettled() {
		ProgramMentor answered = membership(ProgramMentorStatus.REJECTED, ProgramMentorInvitedBy.MENTOR);
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(answered));

		assertThatThrownBy(() -> programService.settleMentorRequest(program(), MENTOR_ID, true))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Mentor request not found");
		assertThatThrownBy(() -> programService.settleMentorRequest(program(), MENTOR_ID, true))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("cannot be decided");
		assertThat(answered.getStatus()).isEqualTo(ProgramMentorStatus.REJECTED);
		verify(programMentorRepository, never()).save(any());
		verifyNoInteractions(notificationService);
	}

	@Test
	void findDetailShowsAnAdminEachStartupsMembersWithTheFounderMarkedAndOnlyAcceptedMentors() {
		UUID adminId = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
		AppUser admin = coordinator();
		admin.setId(adminId);
		admin.setRole(UserRole.ADMIN);
		when(domainLookupService.requireUser(adminId)).thenReturn(admin);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		AppUser founder = mentee(null);
		Organization organization = founder.getOrganization();
		organization.setFounder(founder);
		organization.setDescription("Sağlıkta yapay zekâ");
		AppUser teammate = mentee(null);
		teammate.setId(UUID.fromString("00000000-0000-0000-0000-000000000008"));
		teammate.setFullName("Can Öz");
		teammate.setJobTitle("CTO");
		teammate.setOrganization(organization);
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID))
				.thenReturn(List.of(assignment(PROGRAM_ID)));
		ProgramMentor accepted = membership(ProgramMentorStatus.ACCEPTED, ProgramMentorInvitedBy.COORDINATOR);
		accepted.setRole("Ürün mentoru");
		ProgramMentor pending = membership(ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR);
		pending.setMentorId(UUID.randomUUID());
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID))
				.thenReturn(List.of(accepted, pending));
		when(organizationRepository.findAllById(any())).thenReturn(List.of(organization));
		when(appUserRepository.findByOrganizationIdInOrderByFullNameAsc(List.of(ORG_ID)))
				.thenReturn(List.of(founder, teammate));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(mentor(), coordinator()));

		var detail = programService.findDetail(adminId, PROGRAM_ID);

		assertThat(detail.coordinatorId()).isEqualTo(COORDINATOR_ID);
		assertThat(detail.coordinatorName()).isEqualTo("Demo Coordinator");
		assertThat(detail.startups()).singleElement().satisfies(startup -> {
			assertThat(startup.id()).isEqualTo(ORG_ID);
			assertThat(startup.name()).isEqualTo("NovaMed AI");
			assertThat(startup.description()).isEqualTo("Sağlıkta yapay zekâ");
			assertThat(startup.members()).containsExactly(
					new ProgramMemberSummaryResponse(MENTEE_ID, "Ayşe Kaya", null, true),
					new ProgramMemberSummaryResponse(teammate.getId(), "Can Öz", "CTO", false));
		});
		assertThat(detail.mentors()).singleElement().satisfies(mentor -> {
			assertThat(mentor.mentorId()).isEqualTo(MENTOR_ID);
			assertThat(mentor.status()).isEqualTo(ProgramMentorStatus.ACCEPTED);
			assertThat(mentor.role()).isEqualTo("Ürün mentoru");
		});
	}

	@Test
	void findDetailLetsAnAcceptedMentorIn() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(membership(ProgramMentorStatus.ACCEPTED, ProgramMentorInvitedBy.COORDINATOR)));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		var detail = programService.findDetail(MENTOR_ID, PROGRAM_ID);

		assertThat(detail.id()).isEqualTo(PROGRAM_ID);
		assertThat(detail.startups()).isEmpty();
		assertThat(detail.mentors()).isEmpty();
		verify(appUserRepository, never()).findByOrganizationIdInOrderByFullNameAsc(any());
	}

	@Test
	void findDetailLetsAStartupOfTheProgramIn() {
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(mentee(null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, ORG_ID))
				.thenReturn(Optional.of(assignment(PROGRAM_ID)));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(coordinator()));

		assertThat(programService.findDetail(MENTEE_ID, PROGRAM_ID).name()).isEqualTo("Seed 2026");
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
