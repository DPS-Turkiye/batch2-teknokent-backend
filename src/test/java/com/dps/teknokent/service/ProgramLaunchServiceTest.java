package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.TeamJoinStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateCoordinatorProgramRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.TeamInviteInput;
import com.dps.teknokent.dto.responses.CoordinatorMentorshipResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramTeamInvite;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramTeamInviteRepository;

@ExtendWith(MockitoExtension.class)
class ProgramLaunchServiceTest {

	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private ProgramTeamInviteRepository inviteRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private MailService mailService;
	@Mock
	private CoordinatorMentorshipService mentorshipService;
	@Mock
	private PlatformTransactionManager transactionManager;
	@Mock
	private ProgramEligibilityService programEligibilityService;
	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private ProgramLaunchService service;

	private static final UUID ELIF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID BURCU = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
	private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");

	@Test
	void anAdminCreatesADraftWithItsRulesCoordinatorsAndATeamCodePerTeam() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, UserRole.ADMIN));
		when(appUserRepository.findById(ELIF)).thenReturn(Optional.of(user(ELIF, UserRole.COORDINATOR)));
		when(appUserRepository.findById(BURCU)).thenReturn(Optional.of(user(BURCU, UserRole.COORDINATOR)));

		service.create(ADMIN, request(List.of(ELIF, BURCU), List.of(
				new TeamInviteInput("Kavşak", "Deniz@Kavsak.co"),
				new TeamInviteInput("Mira Bio", "selin@mirabio.com"))));

		ArgumentCaptor<Program> program = ArgumentCaptor.forClass(Program.class);
		verify(programRepository).save(program.capture());
		assertThat(program.getValue().getName()).isEqualTo("METU BİGG 2026");
		assertThat(program.getValue().getStatus()).isEqualTo(ProgramStatus.DRAFT);
		assertThat(program.getValue().getProgramType()).isEqualTo(ProgramType.STARTUP_MENTORING);
		assertThat(program.getValue().getCreatedBy()).isEqualTo(ADMIN);
		assertThat(program.getValue().getDefaultMentorCapacity()).isEqualTo(4);
		assertThat(program.getValue().getCommunicationGapDays()).isEqualTo(14);
		assertThat(program.getValue().getBottleneckFlagDays()).isEqualTo(21);
		ArgumentCaptor<ProgramCoordinator> coordinators = ArgumentCaptor.forClass(ProgramCoordinator.class);
		verify(programCoordinatorRepository, org.mockito.Mockito.times(2)).save(coordinators.capture());
		assertThat(coordinators.getAllValues()).extracting(ProgramCoordinator::getUserId).containsExactly(ELIF, BURCU);
		verify(notificationService, org.mockito.Mockito.times(2))
				.create(any(), any(), any(), org.mockito.ArgumentMatchers.startsWith("/coordinator/programs/"));
		ArgumentCaptor<ProgramTeamInvite> invites = ArgumentCaptor.forClass(ProgramTeamInvite.class);
		verify(inviteRepository, org.mockito.Mockito.times(2)).save(invites.capture());
		assertThat(invites.getAllValues()).extracting(ProgramTeamInvite::getLeadEmail)
				.containsExactly("deniz@kavsak.co", "selin@mirabio.com");
		assertThat(invites.getAllValues()).allSatisfy(invite -> assertThat(invite.getCode()).startsWith("BIGG26-"));
		verify(programService).detail(any(), any());
	}

	@Test
	void aCoordinatorCannotCreateAProgram() {
		when(programService.requireCoordinatorOrAdmin(ELIF)).thenReturn(user(ELIF, UserRole.COORDINATOR));

		assertThatThrownBy(() -> service.create(ELIF, request(List.of(BURCU), List.of())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("admin");
		verify(programRepository, never()).save(any());
	}

	@Test
	void aPeerProgramTakesIndividualStudentsAndKeepsTheDefaultRules() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, UserRole.ADMIN));
		when(appUserRepository.findById(ELIF)).thenReturn(Optional.of(user(ELIF, UserRole.COORDINATOR)));

		service.create(ADMIN, new CreateCoordinatorProgramRequest(null, ProgramType.PEER_MENTORING, "ODTÜ Akran",
				null, null, null, List.of(ELIF), null, null, null, null, null, null, null));

		ArgumentCaptor<Program> program = ArgumentCaptor.forClass(Program.class);
		verify(programRepository).save(program.capture());
		assertThat(program.getValue().getProgramType()).isEqualTo(ProgramType.PEER_MENTORING);
		assertThat(program.getValue().isIndividualMentees()).isTrue();
		assertThat(program.getValue().getDefaultMentorCapacity()).isEqualTo(4);
		assertThat(program.getValue().getMeetingFrequencyDays()).isEqualTo(14);
		verify(inviteRepository, never()).save(any());
	}

	@Test
	void theWizardsPhasesAndSyllabusAreSavedWithTheProgram() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, UserRole.ADMIN));
		List<ReplaceProgramPhasesRequest.PhaseInput> phases = List.of(
				new ReplaceProgramPhasesRequest.PhaseInput("Keşif", LocalDate.of(2026, 11, 3), null, "Rapor"));
		UUID syllabusId = UUID.randomUUID();
		CreateCoordinatorProgramRequest base = request(List.of(), List.of());

		service.create(ADMIN, new CreateCoordinatorProgramRequest(null, base.type(), base.name(), base.description(),
				base.startDate(), base.endDate(), base.coordinatorIds(), 4, 14, 14, 21, List.of(), phases, syllabusId));

		ArgumentCaptor<Program> program = ArgumentCaptor.forClass(Program.class);
		verify(programRepository).save(program.capture());
		verify(programService).writePhases(program.getValue().getId(), phases);
		verify(programService).attachSyllabus(program.getValue(), ADMIN, syllabusId);
	}

	@Test
	void launchingWithoutPhasesLeavesThePhasesAlone() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, UserRole.ADMIN));

		service.create(ADMIN, request(List.of(), List.of()));

		verify(programService, never()).writePhases(any(), any());
	}

	@Test
	void twoTeamsCannotShareALeadEmailAndOnlyApprovedCoordinatorsCanBeAdded() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, UserRole.ADMIN));

		assertThatThrownBy(() -> service.create(ADMIN, request(List.of(), List.of(
				new TeamInviteInput("A", "same@team.co"), new TeamInviteInput("B", "SAME@team.co")))))
				.isInstanceOf(InvalidRequestException.class);

		UUID mentor = UUID.randomUUID();
		when(appUserRepository.findById(mentor)).thenReturn(Optional.of(user(mentor, UserRole.MENTOR)));
		assertThatThrownBy(() -> service.create(ADMIN, request(List.of(mentor), List.of())))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aTeamWhoseLeadJoinedCannotBeEditedOrRemoved() {
		UUID programId = UUID.randomUUID();
		ProgramTeamInvite joined = new ProgramTeamInvite();
		joined.setId(UUID.randomUUID());
		joined.setOrganizationId(UUID.randomUUID());
		when(inviteRepository.findByIdAndProgramId(joined.getId(), programId)).thenReturn(Optional.of(joined));

		assertThatThrownBy(() -> service.removeTeam(ELIF, programId, joined.getId()))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.updateTeam(ELIF, programId, joined.getId(), new TeamInviteInput("X", "x@y.co")))
				.isInstanceOf(InvalidRequestException.class);
		verify(inviteRepository, never()).delete(any());
	}

	@Test
	void theTeamsTabShowsWaitingLeadsWithTheirCode() {
		UUID programId = UUID.randomUUID();
		ProgramTeamInvite waiting = new ProgramTeamInvite();
		waiting.setId(UUID.randomUUID());
		waiting.setTeamName("Pusula");
		waiting.setLeadEmail("ali@pusula.io");
		waiting.setCode("BIGG26-P8LN");
		when(inviteRepository.findByProgramIdOrderByCreatedAtAsc(programId)).thenReturn(List.of(waiting));
		when(programStartupRepository.findByProgramIdIn(List.of(programId))).thenReturn(List.of());

		var rows = service.teams(ELIF, programId);

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.status()).isEqualTo(TeamJoinStatus.WAITING);
			assertThat(row.code()).isEqualTo("BIGG26-P8LN");
			assertThat(row.name()).isEqualTo("Pusula");
		});
	}

	@Test
	void emailingTheCodeRecordsWhenItWentOut() {
		UUID programId = UUID.randomUUID();
		ProgramTeamInvite waiting = waitingInvite(programId);
		when(mailService.isConfigured()).thenReturn(true);
		when(mailService.link("/invite")).thenReturn("https://menton.com.tr/invite");
		when(mailService.sendNow(org.mockito.ArgumentMatchers.eq("ali@pusula.io"), any(),
				org.mockito.ArgumentMatchers.contains("BIGG26-P8LN"))).thenReturn(true);

		var rows = service.sendCode(ELIF, programId, waiting.getId());

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.codeSentAt()).isNotNull();
			assertThat(row.codeSendFailed()).isFalse();
		});
	}

	@Test
	void aRefusedEmailPutsTheTeamUnderSorunluUntilTheEmailIsFixed() {
		UUID programId = UUID.randomUUID();
		ProgramTeamInvite waiting = waitingInvite(programId);
		when(mailService.isConfigured()).thenReturn(true);
		when(mailService.sendNow(any(), any(), any())).thenReturn(false);

		assertThat(service.sendCode(ELIF, programId, waiting.getId())).singleElement()
				.satisfies(row -> assertThat(row.codeSendFailed()).isTrue());

		var fixed = service.updateTeam(ELIF, programId, waiting.getId(), new TeamInviteInput("Pusula", "ali@pusula.com"));
		assertThat(fixed).singleElement().satisfies(row -> {
			assertThat(row.codeSendFailed()).isFalse();
			assertThat(row.codeSentAt()).isNull();
		});
	}

	@Test
	void emailsTheCodeBetweenTwoTransactionsNotInsideOne() {
		UUID programId = UUID.randomUUID();
		ProgramTeamInvite waiting = waitingInvite(programId);
		when(mailService.isConfigured()).thenReturn(true);
		when(mailService.sendNow(any(), any(), any())).thenReturn(true);

		service.sendCode(ELIF, programId, waiting.getId());

		InOrder order = inOrder(transactionManager, mailService);
		order.verify(transactionManager).getTransaction(any());
		order.verify(transactionManager).commit(any());
		order.verify(mailService).sendNow(any(), any(), any());
		order.verify(transactionManager).getTransaction(any());
		order.verify(transactionManager).commit(any());
	}

	@Test
	void doesNotStampALeadAddressThatWasEditedWhileTheEmailWentOut() {
		UUID programId = UUID.randomUUID();
		ProgramTeamInvite waiting = waitingInvite(programId);
		when(mailService.isConfigured()).thenReturn(true);
		when(mailService.sendNow(any(), any(), any())).thenAnswer(call -> {
			waiting.setLeadEmail("ali@pusula.com");
			return true;
		});

		service.sendCode(ELIF, programId, waiting.getId());

		assertThat(waiting.getCodeSentAt()).isNull();
		verify(inviteRepository, never()).save(any());
	}

	@Test
	void theCodeCannotBeEmailedWhereEmailIsOff() {
		UUID programId = UUID.randomUUID();
		ProgramTeamInvite waiting = waitingInvite(programId);

		assertThatThrownBy(() -> service.sendCode(ELIF, programId, waiting.getId()))
				.isInstanceOf(InvalidRequestException.class);
		verify(mailService, never()).sendNow(any(), any(), any());
	}

	@Test
	void theTeamDrawerShowsTheLeadMembersAndOnlyThisTeamsMentorships() {
		UUID programId = UUID.randomUUID();
		AppUser lead = user(UUID.randomUUID(), UserRole.MENTEE);
		lead.setFullName("Gamze Kozak");
		Organization atlas = new Organization();
		atlas.setId(UUID.randomUUID());
		atlas.setName("Atlas AI");
		atlas.setFounder(lead);
		atlas.setInviteCode("METUSTARS-A82K");
		ProgramStartup startup = new ProgramStartup();
		startup.setOrganizationId(atlas.getId());
		when(programStartupRepository.findByProgramIdIn(List.of(programId))).thenReturn(List.of(startup));
		when(organizationRepository.findById(atlas.getId())).thenReturn(Optional.of(atlas));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(atlas.getId())).thenReturn(List.of(lead));
		CoordinatorMentorshipResponse ours = org.mockito.Mockito.mock(CoordinatorMentorshipResponse.class);
		CoordinatorMentorshipResponse theirs = org.mockito.Mockito.mock(CoordinatorMentorshipResponse.class);
		when(ours.organizationId()).thenReturn(atlas.getId());
		when(theirs.organizationId()).thenReturn(UUID.randomUUID());
		when(mentorshipService.list(ELIF, programId)).thenReturn(List.of(ours, theirs));

		var detail = service.teamDetail(ELIF, programId, atlas.getId());

		assertThat(detail.lead().name()).isEqualTo("Gamze Kozak");
		assertThat(detail.code()).isEqualTo("METUSTARS-A82K");
		assertThat(detail.members()).hasSize(1);
		assertThat(detail.mentorships()).containsExactly(ours);
		assertThatThrownBy(() -> service.teamDetail(ELIF, programId, UUID.randomUUID()))
				.isInstanceOf(com.dps.teknokent.exception.ResourceNotFoundException.class);
	}

	private ProgramTeamInvite waitingInvite(UUID programId) {
		Program program = new Program();
		program.setId(programId);
		program.setName("METU BİGG 2026");
		ProgramTeamInvite waiting = new ProgramTeamInvite();
		waiting.setId(UUID.randomUUID());
		waiting.setProgramId(programId);
		waiting.setTeamName("Pusula");
		waiting.setLeadEmail("ali@pusula.io");
		waiting.setCode("BIGG26-P8LN");
		org.mockito.Mockito.lenient().when(programService.requireManagedProgram(ELIF, programId)).thenReturn(program);
		when(inviteRepository.findByIdAndProgramId(waiting.getId(), programId)).thenReturn(Optional.of(waiting));
		org.mockito.Mockito.lenient().when(inviteRepository.findByProgramIdOrderByCreatedAtAsc(programId))
				.thenReturn(List.of(waiting));
		org.mockito.Mockito.lenient().when(programStartupRepository.findByProgramIdIn(List.of(programId)))
				.thenReturn(List.of());
		return waiting;
	}

	private static CreateCoordinatorProgramRequest request(List<UUID> coordinators, List<TeamInviteInput> teams) {
		return new CreateCoordinatorProgramRequest(null, ProgramType.STARTUP_MENTORING, " METU BİGG 2026 ", "Kuluçka", LocalDate.of(2026, 11, 3),
				LocalDate.of(2027, 9, 30), coordinators, 4, 14, 14, 21, teams, null, null);
	}

	private static AppUser user(UUID id, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setFullName("Kişi");
		return user;
	}
}
