package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.CoordinatorAgendaKind;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.ProgramLifecycle;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProgramEventRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.UpdateProgramDetailsRequest;
import com.dps.teknokent.dto.responses.CoordinatorOverviewResponse;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.dto.responses.ProgramEventResponse;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingNote;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;
import com.dps.teknokent.service.CoordinatorProgramService.HealthBucket;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class CoordinatorProgramServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private ProgramPhaseRepository programPhaseRepository;
	@Mock
	private StoredDocumentRepository storedDocumentRepository;
	@Mock
	private ProgramEventRepository programEventRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private BottleneckRepository bottleneckRepository;
	@Mock
	private ActionItemRepository actionItemRepository;
	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private MeetingNoteRepository meetingNoteRepository;
	@Mock
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Mock
	private TeamChangeRequestRepository teamChangeRequestRepository;
	@InjectMocks
	private CoordinatorProgramService service;

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID OTHER_COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID MENTEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
	private static final UUID PENDING_MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000016");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID TERRA_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID LUMEN_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID KAVUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");

	private final LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);

	@Test
	void classifiesByDaysSinceContactThenByOpenIssues() {
		assertThat(CoordinatorProgramService.classify(14, false, 14, 14)).isEqualTo(HealthBucket.RISKY);
		assertThat(CoordinatorProgramService.classify(13, false, 14, 14)).isEqualTo(HealthBucket.HEALTHY);
		assertThat(CoordinatorProgramService.classify(13, true, 14, 14)).isEqualTo(HealthBucket.FOLLOW_UP);
		assertThat(CoordinatorProgramService.classify(10, false, 7, 21)).isEqualTo(HealthBucket.FOLLOW_UP);
		assertThat(CoordinatorProgramService.classify(21, false, 7, 21)).isEqualTo(HealthBucket.RISKY);
	}

	@Test
	void aCoordinatorListsOnlyTheirOwnProgramsWithCounts() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		Program program = program(COORDINATOR_ID);
		program.setStartDate(today.minusDays(35));
		program.setEndDate(today.minusDays(35).plusDays(111));
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID)).thenReturn(List.of(program));
		stubProgramRows(
				List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 4)),
				List.of(membership(MENTOR_ID, ProgramMentorStatus.ACCEPTED),
						membership(PENDING_MENTOR_ID, ProgramMentorStatus.PENDING)),
				List.of(phase("Mentorluk dönemi", today.minusDays(5), today.plusDays(30))));
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(3))),
				List.of(), List.of(), List.of());

		var programs = service.list(COORDINATOR_ID);

		assertThat(programs).hasSize(1);
		var summary = programs.getFirst();
		assertThat(summary.lifecycle()).isEqualTo(ProgramLifecycle.ACTIVE);
		assertThat(summary.coordinatorName()).isEqualTo("Elif Demir");
		assertThat(summary.teamCount()).isEqualTo(2);
		assertThat(summary.mentorCount()).isEqualTo(1);
		assertThat(summary.pendingMentorCount()).isEqualTo(1);
		assertThat(summary.activeMentorshipCount()).isEqualTo(1);
		assertThat(summary.awaitingMatchCount()).isEqualTo(1);
		assertThat(summary.oldestAwaitingMatchDays()).isEqualTo(4);
		assertThat(summary.riskyMentorshipCount()).isZero();
		assertThat(summary.currentPhase().name()).isEqualTo("Mentorluk dönemi");
		assertThat(summary.currentWeek()).isEqualTo(6);
		assertThat(summary.totalWeeks()).isEqualTo(16);
		verify(programRepository, never()).findAllByOrderByCreatedAtDesc();
	}

	@Test
	void anAdminListsEveryProgram() {
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN));
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

		assertThat(service.list(ADMIN_ID)).isEmpty();

		verify(programRepository, never()).findByCreatedByOrderByCreatedAtDesc(any());
	}

	@Test
	void secondaryAdminPermissionBypassesTheCoordinatorAssignmentRestriction() {
		AppUser actor = user(ADMIN_ID, UserRole.ADMIN);
		org.springframework.test.util.ReflectionTestUtils.setField(actor, "role", UserRole.COORDINATOR);
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(actor);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program(COORDINATOR_ID)));

		assertThat(service.requireManagedProgram(ADMIN_ID, PROGRAM_ID).getId()).isEqualTo(PROGRAM_ID);
		assertThat(service.list(ADMIN_ID)).isEmpty();
		verify(programRepository).findAllByOrderByCreatedAtDesc();
	}

	@Test
	void aMenteeCannotListPrograms() {
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(user(MENTEE_ID, UserRole.MENTEE));

		assertThatThrownBy(() -> service.list(MENTEE_ID)).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aCoordinatorCannotOpenAnotherCoordinatorsProgram() {
		when(domainLookupService.requireUser(OTHER_COORDINATOR_ID))
				.thenReturn(user(OTHER_COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program(COORDINATOR_ID)));

		assertThatThrownBy(() -> service.detail(OTHER_COORDINATOR_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theDetailSortsTeamsByHealthAndReportsWhatIsRisky() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(
				List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 1), startup(KAVUN_ID, 6)),
				List.of(membership(MENTOR_ID, ProgramMentorStatus.ACCEPTED)),
				List.of());
		Mentorship terra = mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40));
		Mentorship lumen = mentorship(LUMEN_ID, MentorshipStatus.ACTIVE, today.minusDays(40));
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen"),
						organization(KAVUN_ID, "Kavun")),
				List.of(terra, lumen),
				List.of(meeting(TERRA_ID, 20), meeting(LUMEN_ID, 2)),
				List.of(bottleneck(TERRA_ID, "Pazarlama stratejisi")),
				List.of(actionItem(LUMEN_ID)));
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());
		when(mentorshipRepository.countOrganizationsByMentor(anyCollection(), anyCollection()))
				.thenReturn(List.of(count(MENTOR_ID, 2)));
		MentorProfile profile = new MentorProfile();
		profile.setId(MENTOR_ID);
		profile.setMenteeCapacity(2);
		when(mentorProfileRepository.findAllById(any())).thenReturn(List.of(profile));
		when(appUserRepository.countByProgramIdAndPermissionAndStatus(PROGRAM_ID, UserRole.MENTEE,
				UserAccountStatus.PENDING)).thenReturn(1L);
		when(programMentorRepository.countByProgramIdAndStatusAndInvitedBy(PROGRAM_ID, ProgramMentorStatus.PENDING,
				ProgramMentorInvitedBy.MENTOR)).thenReturn(2L);
		when(organizationRepository.countInProgramByApprovalStatus(PROGRAM_ID, UserAccountStatus.PENDING))
				.thenReturn(3L);
		when(teamChangeRequestRepository.countByProgramIdAndStatus(PROGRAM_ID, TeamChangeStatus.PENDING))
				.thenReturn(4L);

		var detail = service.detail(COORDINATOR_ID, PROGRAM_ID);

		assertThat(detail.health().risky()).isEqualTo(1);
		assertThat(detail.health().followUp()).isEqualTo(1);
		assertThat(detail.health().healthy()).isZero();
		assertThat(detail.health().riskyTopics())
				.extracting(topic -> topic.title() + ":" + topic.teams())
				.containsExactly("Pazarlama stratejisi:1");
		assertThat(detail.awaitingTeams()).singleElement().satisfies(team -> {
			assertThat(team.name()).isEqualTo("Kavun");
			assertThat(team.waitingDays()).isEqualTo(6);
		});
		assertThat(detail.teams().mentored()).isEqualTo(2);
		assertThat(detail.teams().awaitingMatch()).isEqualTo(1);
		assertThat(detail.mentors().full()).isEqualTo(1);
		assertThat(detail.program().riskyMentorshipCount()).isEqualTo(1);
		assertThat(detail.rules().meetingFrequencyDays()).isEqualTo(14);
		assertThat(detail.pendingApprovalCount()).isEqualTo(10);
	}

	@Test
	void aMentorWithoutAnOwnLimitIsFullAtTheProgramsDefaultCapacity() {
		Program program = program(COORDINATOR_ID);
		program.setDefaultMentorCapacity(2);
		stubManagedProgram(program);
		stubProgramRows(List.of(), List.of(membership(MENTOR_ID, ProgramMentorStatus.ACCEPTED)), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());
		when(mentorshipRepository.countOrganizationsByMentor(anyCollection(), anyCollection()))
				.thenReturn(List.of(count(MENTOR_ID, 2)));
		when(mentorProfileRepository.findAllById(any())).thenReturn(List.of());

		var mentors = service.detail(COORDINATOR_ID, PROGRAM_ID).mentors();

		assertThat(mentors.full()).isEqualTo(1);
		assertThat(mentors.withCapacity()).isZero();
	}

	@Test
	void replacingPhasesDeletesTheLeftOutOnesAndFlushesBeforeSavingInOrder() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		service.replacePhases(COORDINATOR_ID, PROGRAM_ID, new ReplaceProgramPhasesRequest(null, List.of(
				new ReplaceProgramPhasesRequest.PhaseInput("  Başvuru ", today, today.plusDays(10), null, null),
				new ReplaceProgramPhasesRequest.PhaseInput("Demo Day", null, null, null, null)), null));

		InOrder order = inOrder(programPhaseRepository);
		order.verify(programPhaseRepository).deleteAll(List.of());
		order.verify(programPhaseRepository).flush();
		@SuppressWarnings("unchecked") // ArgumentCaptor.forClass takes the raw collection class.
		ArgumentCaptor<List<ProgramPhase>> saved = ArgumentCaptor.forClass(List.class);
		order.verify(programPhaseRepository).saveAll(saved.capture());
		assertThat(saved.getValue())
				.extracting(phase -> phase.getPosition() + ":" + phase.getName())
				.containsExactly("0:Başvuru", "1:Demo Day");
	}

	@Test
	void aPhaseCannotEndBeforeItStarts() {
		stubManagedProgram(program(COORDINATOR_ID));

		assertThatThrownBy(() -> service.replacePhases(COORDINATOR_ID, PROGRAM_ID, new ReplaceProgramPhasesRequest(
				null,
				List.of(new ReplaceProgramPhasesRequest.PhaseInput("Seçim", today, today.minusDays(1), null, null)),
				null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(programPhaseRepository, never()).deleteAll(anyList());
	}

	@Test
	void aCoordinatorAddedToTheProgramCanOpenIt() {
		when(domainLookupService.requireUser(OTHER_COORDINATOR_ID))
				.thenReturn(user(OTHER_COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program(COORDINATOR_ID)));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(PROGRAM_ID, OTHER_COORDINATOR_ID))
				.thenReturn(true);

		assertThat(service.requireManagedProgram(OTHER_COORDINATOR_ID, PROGRAM_ID).getId()).isEqualTo(PROGRAM_ID);
	}

	@Test
	void anArchivedProgramCannotBeChangedUntilRestored() {
		Program program = program(COORDINATOR_ID);
		program.setStatus(ProgramStatus.COMPLETED);
		stubManagedProgram(program);

		assertThatThrownBy(() -> service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "Yeni ad", null, null, null, null, null, null, null, null, null, null, null, null, null)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.deleteEvent(COORDINATOR_ID, PROGRAM_ID, UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class);
		verify(programRepository, never()).save(any());
	}

	@Test
	void aDraftIsNeitherArchivedNorRestoredByTheCoordinator() {
		Program program = program(COORDINATOR_ID);
		program.setStatus(ProgramStatus.DRAFT);
		stubManagedProgram(program);

		assertThatThrownBy(() -> service.setArchived(COORDINATOR_ID, PROGRAM_ID, false))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("admin");
		assertThat(program.getStatus()).isEqualTo(ProgramStatus.DRAFT);
		verify(programRepository, never()).save(any());
	}

	@Test
	void aPeerMentoringProgramAlwaysTakesIndividualStudents() {
		Program program = program(COORDINATOR_ID);
		program.setProgramType(ProgramType.PEER_MENTORING);
		stubManagedProgram(program);
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var detail = service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "Akran", null, null, null, null, null, null, null, null, null, null, null, null, false));

		assertThat(program.isIndividualMentees()).isTrue();
		assertThat(detail.rules().individualMentees()).isTrue();
		assertThat(detail.program().type()).isEqualTo(ProgramType.PEER_MENTORING);
	}

	@Test
	void anAdminLaunchesADraftAndItsCoordinatorsLeaveOutTheAdmin() {
		Program program = program(ADMIN_ID);
		program.setStatus(ProgramStatus.DRAFT);
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		ProgramCoordinator assigned = new ProgramCoordinator();
		assigned.setProgramId(PROGRAM_ID);
		assigned.setUserId(COORDINATOR_ID);
		when(programCoordinatorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(assigned));
		when(programCoordinatorRepository.findByProgramIdIn(any())).thenReturn(List.of(assigned));
		when(appUserRepository.findAllById(any()))
				.thenReturn(List.of(user(ADMIN_ID, UserRole.ADMIN), user(COORDINATOR_ID, UserRole.COORDINATOR)));
		when(programPhaseRepository.findByProgramIdInOrderByPositionAsc(anyList())).thenReturn(List.of());
		when(programStartupRepository.findByProgramIdIn(anyList())).thenReturn(List.of());
		when(programMentorRepository.findByProgramIdIn(anyList())).thenReturn(List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var detail = service.launch(ADMIN_ID, PROGRAM_ID);

		assertThat(program.getStatus()).isEqualTo(ProgramStatus.ACTIVE);
		assertThat(detail.program().lifecycle()).isNotEqualTo(ProgramLifecycle.DRAFT);
		assertThat(detail.coordinators()).extracting(CoordinatorProgramResponse.CoordinatorRef::id)
				.containsExactly(COORDINATOR_ID);
		assertThat(detail.program().coordinatorId()).isEqualTo(COORDINATOR_ID);
		verify(programRepository).save(program);
	}

	@Test
	void aDraftWhoseOnlyCoordinatorWasRejectedIsNotLaunched() {
		Program program = program(ADMIN_ID);
		program.setStatus(ProgramStatus.DRAFT);
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		ProgramCoordinator assigned = new ProgramCoordinator();
		assigned.setProgramId(PROGRAM_ID);
		assigned.setUserId(COORDINATOR_ID);
		when(programCoordinatorRepository.findByProgramIdIn(any())).thenReturn(List.of(assigned));
		AppUser rejected = user(COORDINATOR_ID, UserRole.COORDINATOR);
		rejected.setStatus(UserAccountStatus.REJECTED);
		when(appUserRepository.findAllById(any())).thenReturn(List.of(user(ADMIN_ID, UserRole.ADMIN), rejected));

		assertThatThrownBy(() -> service.launch(ADMIN_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Assign a coordinator");
		assertThat(program.getStatus()).isEqualTo(ProgramStatus.DRAFT);
	}

	@Test
	void onlyAnAdminLaunchesOnlyADraftAndOnlyWithACoordinator() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		assertThatThrownBy(() -> service.launch(COORDINATOR_ID, PROGRAM_ID))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

		Program program = program(ADMIN_ID);
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		assertThatThrownBy(() -> service.launch(ADMIN_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("already");

		program.setStatus(ProgramStatus.DRAFT);
		when(appUserRepository.findAllById(any())).thenReturn(List.of(user(ADMIN_ID, UserRole.ADMIN)));
		assertThatThrownBy(() -> service.launch(ADMIN_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("coordinator");
		assertThat(program.getStatus()).isEqualTo(ProgramStatus.DRAFT);
		verify(programRepository, never()).save(any());
	}

	@Test
	void anAdminOnlyViewsProgramSettings() {
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN));

		assertThatThrownBy(() -> service.updateDetails(ADMIN_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "Yeni ad", null, null, null, null, null, null, null, null, null, null, null, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("admin views");
		verify(programRepository, never()).save(any());
	}

	@Test
	void archivingCompletesTheProgramAndRestoringReopensIt() {
		Program program = program(COORDINATOR_ID);
		stubManagedProgram(program);
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var archived = service.setArchived(COORDINATOR_ID, PROGRAM_ID, true);
		assertThat(program.getStatus()).isEqualTo(ProgramStatus.COMPLETED);
		assertThat(archived.program().status()).isEqualTo(ProgramStatus.COMPLETED);

		service.setArchived(COORDINATOR_ID, PROGRAM_ID, false);
		assertThat(program.getStatus()).isEqualTo(ProgramStatus.ACTIVE);
	}

	@Test
	void updatingDetailsKeepsRulesThatWereNotSent() {
		Program program = program(COORDINATOR_ID);
		program.setStaleAfterDays(21);
		stubManagedProgram(program);
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var detail = service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, " METU Stars 2026 ", "  ", today, today.plusDays(100), 10, null, false, null, null, null, null,
				null, null, null));

		assertThat(program.getName()).isEqualTo("METU Stars 2026");
		assertThat(program.getDescription()).isNull();
		assertThat(detail.rules().meetingFrequencyDays()).isEqualTo(10);
		assertThat(detail.rules().staleAfterDays()).isEqualTo(21);
		assertThat(detail.rules().meetingNoteRequired()).isFalse();
		assertThat(detail.rules().autoMatchSuggestions()).isTrue();
		verify(programRepository).save(program);
	}

	@Test
	void updatingDetailsSetsAndClearsTheEligibilityRules() {
		Program program = program(COORDINATOR_ID);
		stubManagedProgram(program);
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var detail = service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "İŞKUR", null, null, null, null, null, null, null, null, null, null, " @METU.edu.tr ", true,
				true));

		assertThat(detail.rules().eligibleEmailDomain()).isEqualTo("metu.edu.tr");
		assertThat(detail.rules().rosterOnly()).isTrue();
		assertThat(detail.rules().individualMentees()).isTrue();

		service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "İŞKUR", null, null, null, null, null, null, null, null, null, null, "", null, null));
		assertThat(program.getEligibleEmailDomain()).isNull();
		assertThat(program.isRosterOnly()).isTrue();
	}

	@Test
	void anInvalidEligibleDomainIsRefused() {
		stubManagedProgram(program(COORDINATOR_ID));

		assertThatThrownBy(() -> service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "İŞKUR", null, null, null, null, null, null, null, null, null, null, "metu edu", null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("metu edu");
		verify(programRepository, never()).save(any());
	}

	@Test
	void aProgramCannotEndBeforeItStarts() {
		stubManagedProgram(program(COORDINATOR_ID));

		assertThatThrownBy(() -> service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "METU Stars", null, today, today.minusDays(1), null, null, null, null, null, null, null, null,
				null, null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(programRepository, never()).save(any());
	}

	@Test
	void createsAnEventForTheProgram() {
		stubManagedProgram(program(COORDINATOR_ID));
		when(programEventRepository.save(any(ProgramEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
		Instant startsAt = Instant.parse("2026-10-17T20:59:00Z");

		var event = service.createEvent(COORDINATOR_ID, PROGRAM_ID, new CreateProgramEventRequest(
				null, " Ara Rapor Teslimi ", ProgramEventKind.DEADLINE, startsAt, null, null));

		assertThat(event.title()).isEqualTo("Ara Rapor Teslimi");
		assertThat(event.kind()).isEqualTo(ProgramEventKind.DEADLINE);
		assertThat(event.programId()).isEqualTo(PROGRAM_ID);
	}

	@Test
	void anEventCannotEndBeforeItStarts() {
		stubManagedProgram(program(COORDINATOR_ID));
		Instant startsAt = Instant.parse("2026-10-17T10:00:00Z");

		assertThatThrownBy(() -> service.createEvent(COORDINATOR_ID, PROGRAM_ID, new CreateProgramEventRequest(
				null, "Atölye", ProgramEventKind.EVENT, startsAt, startsAt.minusSeconds(60), null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(programEventRepository, never()).save(any());
	}

	@Test
	void deletingAnEventOfAnotherProgramIsNotFound() {
		stubManagedProgram(program(COORDINATOR_ID));
		UUID eventId = UUID.randomUUID();
		when(programEventRepository.findByIdAndProgramId(eventId, PROGRAM_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteEvent(COORDINATOR_ID, PROGRAM_ID, eventId))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void theHomeSumsUpTheWeekAndWhatNeedsADecision() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID))
				.thenReturn(List.of(program(COORDINATOR_ID)));
		stubProgramRows(
				List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 1), startup(KAVUN_ID, 6)),
				List.of(membership(MENTOR_ID, ProgramMentorStatus.ACCEPTED),
						membership(PENDING_MENTOR_ID, ProgramMentorStatus.PENDING)),
				List.of());
		Meeting terraPast = meeting(TERRA_ID, 20);
		Meeting lumenRecent = meeting(LUMEN_ID, 3);
		lumenRecent.setId(UUID.randomUUID());
		Meeting terraNext = meeting(TERRA_ID, 0);
		terraNext.setId(UUID.randomUUID());
		terraNext.setStatus(MeetingStatus.SCHEDULED);
		terraNext.setScheduledAt(Instant.now().plus(2, ChronoUnit.DAYS));
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen"),
						organization(KAVUN_ID, "Kavun")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40)),
						mentorship(LUMEN_ID, MentorshipStatus.ACTIVE, today.minusDays(40))),
				List.of(terraNext, lumenRecent, terraPast),
				List.of(bottleneck(TERRA_ID, "Pazarlama stratejisi")),
				List.of(actionItem(LUMEN_ID)));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(
				user(COORDINATOR_ID, UserRole.COORDINATOR), user(PENDING_MENTOR_ID, UserRole.MENTOR)));
		MentorshipRequest slow = new MentorshipRequest();
		slow.setMentor(user(MENTOR_ID, UserRole.MENTOR));
		slow.setCreatedAt(Instant.now().minus(5, ChronoUnit.DAYS));
		when(mentorshipRequestRepository.findByOrganizationIdInAndStatus(anyCollection(),
				eq(MentorshipRequestStatus.PENDING)))
				.thenReturn(List.of(slow));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(),
				any()))
				.thenReturn(List.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBetween(
				anyCollection(), anyCollection(), any(), any()))
				.thenReturn(List.of());
		when(meetingNoteRepository.findByMeetingIdIn(anyCollection())).thenReturn(List.of());

		var home = service.overview(COORDINATOR_ID, null);

		assertThat(home.programs()).extracting(option -> option.name()).containsExactly("METU Stars 2026");
		var metrics = home.metrics();
		assertThat(metrics.activeMentorships()).isEqualTo(2);
		assertThat(metrics.mentoredTeams()).isEqualTo(2);
		assertThat(metrics.activeMentors()).isEqualTo(1);
		assertThat(metrics.staleMentorships()).isEqualTo(1);
		assertThat(metrics.overdueActions()).isEqualTo(1);
		assertThat(metrics.openBottlenecks()).isEqualTo(1);
		assertThat(metrics.slowMentors()).isEqualTo(1);
		assertThat(home.nextMeeting().meetingId()).isEqualTo(terraNext.getId());
		assertThat(home.nextMeeting().sequence()).isEqualTo(2);
		assertThat(home.nextMeeting().title()).isEqualTo("Mentorluk görüşmesi");
		assertThat(home.agenda()).singleElement()
				.satisfies(item -> assertThat(item.kind()).isEqualTo(CoordinatorAgendaKind.MEETING));
		assertThat(home.actions().awaitingTeams()).singleElement()
				.satisfies(team -> assertThat(team.name()).isEqualTo("Kavun"));
		assertThat(home.actions().pendingMentors()).singleElement()
				.satisfies(mentor -> assertThat(mentor.mentorId()).isEqualTo(PENDING_MENTOR_ID));
		assertThat(home.actions().missingNotes()).singleElement()
				.satisfies(note -> assertThat(note.meetingId()).isEqualTo(lumenRecent.getId()));
	}

	@Test
	void updatingDetailsChangesEveryRuleThatIsSent() {
		Program program = program(COORDINATOR_ID);
		stubManagedProgram(program);
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var rules = service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "METU Stars", " Hızlandırma ", null, today, null, 30, null, false, 6, 10, 45, null, null, true))
				.rules();

		assertThat(rules.staleAfterDays()).isEqualTo(30);
		assertThat(rules.autoMatchSuggestions()).isFalse();
		assertThat(rules.defaultMentorCapacity()).isEqualTo(6);
		assertThat(rules.communicationGapDays()).isEqualTo(10);
		assertThat(rules.bottleneckFlagDays()).isEqualTo(45);
		assertThat(rules.individualMentees()).isTrue();
		assertThat(rules.meetingFrequencyDays()).isEqualTo(14);
		assertThat(rules.meetingNoteRequired()).isTrue();
		assertThat(program.getDescription()).isEqualTo("Hızlandırma");
		// An end date alone is fine: there is no start to come after.
		assertThat(program.getEndDate()).isEqualTo(today);
	}

	@Test
	void aPhaseSentBackWithItsIdIsUpdatedInPlaceAndTheOthersAreReplaced() {
		ProgramPhase kept = phase("Başvuru", today, today.plusDays(5));
		kept.setPosition(1);
		ProgramPhase dropped = phase("Seçim", today.plusDays(6), today.plusDays(9));
		when(programPhaseRepository.findByProgramIdOrderByPositionAsc(PROGRAM_ID)).thenReturn(List.of(dropped, kept));
		List<Integer> positionAtFlush = new ArrayList<>();
		doAnswer(invocation -> {
			positionAtFlush.add(kept.getPosition());
			return null;
		}).when(programPhaseRepository).flush();
		UUID unknownId = UUID.randomUUID();

		service.writePhases(PROGRAM_ID, List.of(
				new ReplaceProgramPhasesRequest.PhaseInput("Hazırlık", null, null, " İş planı ", unknownId),
				new ReplaceProgramPhasesRequest.PhaseInput(" Başvuru ve seçim ", today, null, null, kept.getId()),
				new ReplaceProgramPhasesRequest.PhaseInput("Kopya", null, null, null, kept.getId())));

		verify(programPhaseRepository).deleteAll(List.of(dropped));
		// Parked below zero while the new rows take their places, or the unique position would collide.
		assertThat(positionAtFlush).singleElement().satisfies(position -> assertThat(position).isNegative());
		@SuppressWarnings("unchecked") // ArgumentCaptor.forClass takes the raw collection class.
		ArgumentCaptor<List<ProgramPhase>> saved = ArgumentCaptor.forClass(List.class);
		verify(programPhaseRepository).saveAll(saved.capture());
		List<ProgramPhase> phases = saved.getValue();
		assertThat(phases).extracting(ProgramPhase::getPosition).containsExactly(0, 1, 2);
		assertThat(phases).extracting(ProgramPhase::getName).containsExactly("Hazırlık", "Başvuru ve seçim", "Kopya");
		assertThat(phases.get(1)).isSameAs(kept);
		assertThat(kept.getEndDate()).isNull();
		assertThat(phases.get(0).getId()).isNotNull().isNotEqualTo(unknownId);
		assertThat(phases.get(0).getProgramId()).isEqualTo(PROGRAM_ID);
		assertThat(phases.get(0).getExpectedOutput()).isEqualTo("İş planı");
		// The second use of the kept id is a phase of its own.
		assertThat(phases.get(2)).isNotSameAs(kept);
		assertThat(phases.get(2).getId()).isNotNull().isNotEqualTo(kept.getId());
	}

	@Test
	void someoneElsesFileAFileOfAnotherKindOrAMissingOneIsNotASyllabus() {
		Program program = program(COORDINATOR_ID);
		UUID foreignId = UUID.randomUUID();
		UUID cvId = UUID.randomUUID();
		UUID missingId = UUID.randomUUID();
		when(storedDocumentRepository.findById(foreignId))
				.thenReturn(Optional.of(document(foreignId, OTHER_COORDINATOR_ID, DocumentKind.WEEKLY_PROGRAM)));
		when(storedDocumentRepository.findById(cvId))
				.thenReturn(Optional.of(document(cvId, COORDINATOR_ID, DocumentKind.CV)));
		when(storedDocumentRepository.findById(missingId)).thenReturn(Optional.empty());

		for (UUID documentId : List.of(foreignId, cvId, missingId)) {
			assertThatThrownBy(() -> service.attachSyllabus(program, COORDINATOR_ID, documentId))
					.isInstanceOf(ResourceNotFoundException.class)
					.hasMessage("Syllabus not found: " + documentId);
		}
		assertThat(program.getSyllabusDocumentId()).isNull();
		verify(programRepository, never()).save(any());
	}

	@Test
	void theActorsOwnWeeklyProgramOrOlderSyllabusBecomesTheProgramsFile() {
		Program program = program(COORDINATOR_ID);
		UUID weeklyId = UUID.randomUUID();
		UUID syllabusId = UUID.randomUUID();
		when(storedDocumentRepository.findById(weeklyId))
				.thenReturn(Optional.of(document(weeklyId, COORDINATOR_ID, DocumentKind.WEEKLY_PROGRAM)));
		when(storedDocumentRepository.findById(syllabusId))
				.thenReturn(Optional.of(document(syllabusId, COORDINATOR_ID, DocumentKind.SYLLABUS)));

		service.attachSyllabus(program, COORDINATOR_ID, weeklyId);
		assertThat(program.getSyllabusDocumentId()).isEqualTo(weeklyId);

		service.attachSyllabus(program, COORDINATOR_ID, syllabusId);
		assertThat(program.getSyllabusDocumentId()).isEqualTo(syllabusId);
		verify(programRepository, times(2)).save(program);
	}

	@Test
	void attachingNothingOrTheFileAlreadyAttachedLeavesTheProgramAlone() {
		Program program = program(COORDINATOR_ID);
		UUID currentId = UUID.randomUUID();
		program.setSyllabusDocumentId(currentId);

		service.attachSyllabus(program, COORDINATOR_ID, null);
		service.attachSyllabus(program, COORDINATOR_ID, currentId);

		assertThat(program.getSyllabusDocumentId()).isEqualTo(currentId);
		verifyNoInteractions(storedDocumentRepository, programRepository);
	}

	@Test
	void replacingPhasesWithAnUploadedWeeklyProgramShowsItAsTheProgramsSyllabus() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());
		UUID documentId = UUID.randomUUID();
		StoredDocument document = document(documentId, COORDINATOR_ID, DocumentKind.WEEKLY_PROGRAM);
		document.setOriginalFilename("haftalik-program.pdf");
		document.setSizeBytes(2048);
		when(storedDocumentRepository.findById(documentId)).thenReturn(Optional.of(document));

		var syllabus = service.replacePhases(COORDINATOR_ID, PROGRAM_ID,
				new ReplaceProgramPhasesRequest(null, List.of(), documentId)).syllabus();

		assertThat(syllabus.id()).isEqualTo(documentId);
		assertThat(syllabus.filename()).isEqualTo("haftalik-program.pdf");
		assertThat(syllabus.sizeBytes()).isEqualTo(2048);
		assertThat(syllabus.kind()).isEqualTo(DocumentKind.WEEKLY_PROGRAM);
	}

	@Test
	void aSyllabusWhoseFileIsGoneIsLeftOutOfTheDetail() {
		Program program = program(COORDINATOR_ID);
		UUID documentId = UUID.randomUUID();
		program.setSyllabusDocumentId(documentId);
		stubManagedProgram(program);
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());
		when(storedDocumentRepository.findById(documentId)).thenReturn(Optional.empty());

		assertThat(service.detail(COORDINATOR_ID, PROGRAM_ID).syllabus()).isNull();
	}

	@Test
	void listsTheProgramsEventsAsTheRepositoryOrdersThem() {
		stubManagedProgram(program(COORDINATOR_ID));
		ProgramEvent opening = event("Açılış", ProgramEventKind.EVENT, Instant.parse("2026-10-01T09:00:00Z"));
		opening.setEndsAt(Instant.parse("2026-10-01T11:00:00Z"));
		opening.setDescription("Tanışma");
		ProgramEvent report = event("Ara rapor", ProgramEventKind.DEADLINE, Instant.parse("2026-11-01T20:59:00Z"));
		when(programEventRepository.findByProgramIdOrderByStartsAtAsc(PROGRAM_ID)).thenReturn(List.of(opening, report));

		var events = service.listEvents(COORDINATOR_ID, PROGRAM_ID);

		assertThat(events).extracting(ProgramEventResponse::title).containsExactly("Açılış", "Ara rapor");
		assertThat(events.getFirst()).isEqualTo(new ProgramEventResponse(opening.getId(), PROGRAM_ID, "Açılış",
				ProgramEventKind.EVENT, opening.getStartsAt(), opening.getEndsAt(), "Tanışma"));
	}

	@Test
	void anEventKeepsItsEndAndRecordsWhoCreatedIt() {
		stubManagedProgram(program(COORDINATOR_ID));
		when(programEventRepository.save(any(ProgramEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
		Instant startsAt = Instant.parse("2026-10-17T10:00:00Z");
		Instant endsAt = startsAt.plus(2, ChronoUnit.HOURS);

		service.createEvent(COORDINATOR_ID, PROGRAM_ID, new CreateProgramEventRequest(
				null, "Atölye", ProgramEventKind.EVENT, startsAt, endsAt, "  Müşteri görüşmeleri  "));

		ArgumentCaptor<ProgramEvent> saved = ArgumentCaptor.forClass(ProgramEvent.class);
		verify(programEventRepository).save(saved.capture());
		assertThat(saved.getValue().getEndsAt()).isEqualTo(endsAt);
		assertThat(saved.getValue().getDescription()).isEqualTo("Müşteri görüşmeleri");
		assertThat(saved.getValue().getCreatedBy()).isEqualTo(COORDINATOR_ID);
		assertThat(saved.getValue().getCreatedAt()).isNotNull();
	}

	@Test
	void deletesAnEventOfTheProgram() {
		stubManagedProgram(program(COORDINATOR_ID));
		ProgramEvent event = event("Atölye", ProgramEventKind.EVENT, Instant.parse("2026-10-17T10:00:00Z"));
		when(programEventRepository.findByIdAndProgramId(event.getId(), PROGRAM_ID)).thenReturn(Optional.of(event));

		service.deleteEvent(COORDINATOR_ID, PROGRAM_ID, event.getId());

		verify(programEventRepository).delete(event);
	}

	@Test
	void theHomeOfAProgramWithoutTeamsHasNothingToCountOrDecide() {
		stubCoordinatorHome(program(COORDINATOR_ID));
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(),
				any()))
				.thenReturn(List.of());

		var home = service.overview(COORDINATOR_ID, null);

		assertThat(home.metrics()).isEqualTo(new CoordinatorOverviewResponse.Metrics(0, 0, 0, 0, 0, 0, 0));
		assertThat(home.nextMeeting()).isNull();
		assertThat(home.agenda()).isEmpty();
		assertThat(home.actions().pendingMentors()).isEmpty();
		assertThat(home.actions().awaitingTeams()).isEmpty();
		assertThat(home.actions().missingNotes()).isEmpty();
		verifyNoInteractions(actionItemRepository, mentorshipRequestRepository, meetingNoteRepository);
	}

	@Test
	void anAdminWithNoProgramsGetsAnEmptyHome() {
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN));
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

		var home = service.overview(ADMIN_ID, null);

		assertThat(home.programs()).isEmpty();
		assertThat(home.agenda()).isEmpty();
		verifyNoInteractions(programEventRepository);
	}

	@Test
	void theAgendaListsThisWeeksMeetingsEventsAndDueActionsInTimeOrder() {
		stubCoordinatorHome(program(COORDINATOR_ID));
		stubProgramRows(List.of(startup(TERRA_ID, 1)), List.of(), List.of());
		Instant now = Instant.now();
		Meeting rehearsal = scheduled(TERRA_ID, now.plus(1, ChronoUnit.DAYS));
		rehearsal.setMentor(null);
		rehearsal.setAgenda("  Demo provası ");
		Meeting afterThisWeek = scheduled(TERRA_ID, now.plus(10, ChronoUnit.DAYS));
		Meeting held = held(TERRA_ID, 1);
		held.setNotes("Müşteri görüşmeleri konuşuldu.");
		stubTeamData(List.of(organization(TERRA_ID, "Terra")), List.of(), List.of(afterThisWeek, rehearsal, held),
				List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(),
				any()))
				.thenReturn(List.of(
						event("Atölye", ProgramEventKind.EVENT, now.plus(2, ChronoUnit.DAYS)),
						event("Ara rapor", ProgramEventKind.DEADLINE, now.plus(3, ChronoUnit.DAYS))));
		ActionItem due = actionItem(TERRA_ID);
		due.setTitle("Sunumu güncelle");
		due.setDueDate(today.plusDays(4));
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBetween(
				anyCollection(), anyCollection(), any(), any()))
				.thenReturn(List.of(due));

		var home = service.overview(COORDINATOR_ID, null);

		assertThat(home.agenda())
				.extracting(CoordinatorOverviewResponse.AgendaItem::kind,
						CoordinatorOverviewResponse.AgendaItem::title,
						CoordinatorOverviewResponse.AgendaItem::subtitle,
						CoordinatorOverviewResponse.AgendaItem::allDay)
				.containsExactly(
						tuple(CoordinatorAgendaKind.MEETING, "Demo provası", "Mentor ↔ Takım", false),
						tuple(CoordinatorAgendaKind.EVENT, "Atölye", "METU Stars 2026", false),
						tuple(CoordinatorAgendaKind.DEADLINE, "Ara rapor", "METU Stars 2026", false),
						tuple(CoordinatorAgendaKind.ACTION, "Sunumu güncelle", "Takım", true));
		assertThat(home.agenda().getLast().at())
				.isEqualTo(today.plusDays(4).atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant());
		// The next meeting is the next one with a mentor, even when it is beyond this week's agenda.
		assertThat(home.nextMeeting().meetingId()).isEqualTo(afterThisWeek.getId());
		assertThat(home.actions().missingNotes()).isEmpty();
	}

	@Test
	void theNextMeetingIsTheSoonestUpcomingOneNumberedWithinItsMentorAndTeam() {
		stubCoordinatorHome(program(COORDINATOR_ID));
		stubProgramRows(List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 1)), List.of(), List.of());
		Instant now = Instant.now();
		Meeting next = scheduled(TERRA_ID, now.plus(1, ChronoUnit.DAYS));
		next.setAgenda(" Yatırım turu hazırlığı ");
		next.setDurationMinutes(45);
		next.setMeetLink("https://meet.test/abc");
		next.getMentor().setJobTitle("CTO");
		AppUser founder = user(MENTEE_ID, UserRole.MENTEE);
		founder.setFullName("Deniz Kurucu");
		next.getOrganization().setFounder(founder);
		Meeting later = scheduled(TERRA_ID, now.plus(3, ChronoUnit.DAYS));
		Meeting missed = scheduled(TERRA_ID, now.minus(1, ChronoUnit.HOURS));
		Meeting earlier = held(TERRA_ID, 10);
		Meeting cancelled = held(TERRA_ID, 12);
		cancelled.setStatus(MeetingStatus.CANCELLED);
		Meeting withAnotherMentor = held(TERRA_ID, 6);
		withAnotherMentor.setMentor(user(PENDING_MENTOR_ID, UserRole.MENTOR));
		Meeting ofAnotherTeam = held(LUMEN_ID, 5);
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen")),
				List.of(),
				List.of(later, next, missed, ofAnotherTeam, withAnotherMentor, earlier, cancelled),
				List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(),
				any()))
				.thenReturn(List.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBetween(
				anyCollection(), anyCollection(), any(), any()))
				.thenReturn(List.of());

		var nextMeeting = service.overview(COORDINATOR_ID, null).nextMeeting();

		assertThat(nextMeeting.meetingId()).isEqualTo(next.getId());
		assertThat(nextMeeting.title()).isEqualTo("Yatırım turu hazırlığı");
		assertThat(nextMeeting.durationMinutes()).isEqualTo(45);
		assertThat(nextMeeting.meetLink()).isEqualTo("https://meet.test/abc");
		// This one, the missed one and the one held ten days ago; not the cancelled, other-mentor or other-team ones.
		assertThat(nextMeeting.sequence()).isEqualTo(3);
		assertThat(nextMeeting.mentorId()).isEqualTo(MENTOR_ID);
		assertThat(nextMeeting.mentorTitle()).isEqualTo("CTO");
		assertThat(nextMeeting.organizationId()).isEqualTo(TERRA_ID);
		assertThat(nextMeeting.founderName()).isEqualTo("Deniz Kurucu");
	}

	@Test
	void aRecentMeetingIsMissingItsNoteOnlyWhenNothingWasWrittenAnywhere() {
		stubCoordinatorHome(program(COORDINATOR_ID));
		stubProgramRows(List.of(startup(TERRA_ID, 1)), List.of(), List.of());
		Meeting withNotes = held(TERRA_ID, 2);
		withNotes.setNotes("Pazar araştırması konuşuldu.");
		Meeting withOutcomes = held(TERRA_ID, 3);
		withOutcomes.setOutcomes("Karar: pilot müşteri.");
		Meeting withLiveNote = held(TERRA_ID, 4);
		Meeting withEmptyLiveNote = held(TERRA_ID, 5);
		Meeting cancelled = held(TERRA_ID, 6);
		cancelled.setStatus(MeetingStatus.CANCELLED);
		Meeting withoutMentor = held(TERRA_ID, 7);
		withoutMentor.setMentor(null);
		Meeting tooOld = held(TERRA_ID, 20);
		stubTeamData(List.of(organization(TERRA_ID, "Terra")), List.of(),
				List.of(withNotes, withOutcomes, withLiveNote, withEmptyLiveNote, cancelled, withoutMentor, tooOld),
				List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(),
				any()))
				.thenReturn(List.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBetween(
				anyCollection(), anyCollection(), any(), any()))
				.thenReturn(List.of());
		when(meetingNoteRepository.findByMeetingIdIn(
				List.of(withLiveNote.getId(), withEmptyLiveNote.getId(), withoutMentor.getId())))
				.thenReturn(List.of(liveNote(withLiveNote, "Canlı not"), liveNote(withEmptyLiveNote, "  ")));

		var missing = service.overview(COORDINATOR_ID, null).actions().missingNotes();

		assertThat(missing)
				.extracting(CoordinatorOverviewResponse.MissingNote::meetingId,
						CoordinatorOverviewResponse.MissingNote::mentorName)
				.containsExactly(
						tuple(withEmptyLiveNote.getId(), "Kullanıcı"),
						tuple(withoutMentor.getId(), "Mentor"));
	}

	@Test
	void plannedMentorshipsCountAsMatchedButOnlyActiveOnesGoStaleAndClosedOnesDoNotCount() {
		stubCoordinatorHome(program(COORDINATOR_ID));
		stubProgramRows(List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 2), startup(KAVUN_ID, 3)), List.of(),
				List.of());
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen"),
						organization(KAVUN_ID, "Kavun")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.PLANNED, today.minusDays(40)),
						mentorship(LUMEN_ID, MentorshipStatus.COMPLETED, today.minusDays(40)),
						mentorship(KAVUN_ID, MentorshipStatus.ACTIVE, today.minusDays(40))),
				List.of(), List.of(), List.of());
		when(mentorshipRequestRepository.findByOrganizationIdInAndStatus(anyCollection(),
				eq(MentorshipRequestStatus.PENDING)))
				.thenReturn(List.of(
						pendingRequest(MENTOR_ID, Instant.now().minus(1, ChronoUnit.DAYS)),
						pendingRequest(MENTOR_ID, null),
						pendingRequest(PENDING_MENTOR_ID, Instant.now().minus(4, ChronoUnit.DAYS)),
						pendingRequest(PENDING_MENTOR_ID, Instant.now().minus(9, ChronoUnit.DAYS))));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(),
				any()))
				.thenReturn(List.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBetween(
				anyCollection(), anyCollection(), any(), any()))
				.thenReturn(List.of());

		var home = service.overview(COORDINATOR_ID, null);

		assertThat(home.metrics().activeMentorships()).isEqualTo(2);
		assertThat(home.metrics().mentoredTeams()).isEqualTo(2);
		assertThat(home.metrics().staleMentorships()).isEqualTo(1);
		// Only the mentor holding requests for more than three days, and once however many they hold.
		assertThat(home.metrics().slowMentors()).isEqualTo(1);
		assertThat(home.actions().awaitingTeams())
				.extracting(CoordinatorOverviewResponse.AwaitingTeam::name)
				.containsExactly("Lumen");
	}

	@Test
	void aMentorshipMadeBeforeProgramsWereRecordedIsCountedOnceAcrossItsTeamsPrograms() {
		Program first = program(COORDINATOR_ID);
		Program second = program(COORDINATOR_ID);
		second.setId(UUID.randomUUID());
		second.setName("BİGG 2026");
		stubCoordinatorHome(first, second);
		ProgramStartup inSecond = startup(TERRA_ID, 1);
		inSecond.setProgramId(second.getId());
		stubProgramRows(List.of(startup(TERRA_ID, 1), inSecond), List.of(), List.of());
		stubTeamData(List.of(organization(TERRA_ID, "Terra")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(2))),
				List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(),
				any()))
				.thenReturn(List.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBetween(
				anyCollection(), anyCollection(), any(), any()))
				.thenReturn(List.of());

		var home = service.overview(COORDINATOR_ID, null);

		assertThat(home.programs()).extracting(CoordinatorOverviewResponse.ProgramOption::name)
				.containsExactly("METU Stars 2026", "BİGG 2026");
		assertThat(home.metrics().activeMentorships()).isEqualTo(1);
		assertThat(home.metrics().mentoredTeams()).isEqualTo(1);
	}

	@Test
	void pendingMentorsAreListedLongestWaitingFirstAndAnUnknownAccountStillShows() {
		stubCoordinatorHome(program(COORDINATOR_ID));
		UUID unknownMentorId = UUID.randomUUID();
		ProgramMentor waiting = membership(unknownMentorId, ProgramMentorStatus.PENDING);
		waiting.setCreatedAt(today.minusDays(5).atTime(12, 0).atZone(MeetingSlotTimes.APP_ZONE).toInstant());
		ProgramMentor undated = membership(PENDING_MENTOR_ID, ProgramMentorStatus.PENDING);
		stubProgramRows(List.of(), List.of(undated, membership(MENTOR_ID, ProgramMentorStatus.ACCEPTED), waiting),
				List.of());
		AppUser known = user(PENDING_MENTOR_ID, UserRole.MENTOR);
		known.setFullName("Selin Ar");
		when(appUserRepository.findAllById(any()))
				.thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR), known));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(),
				any()))
				.thenReturn(List.of());

		var pending = service.overview(COORDINATOR_ID, null).actions().pendingMentors();

		assertThat(pending)
				.extracting(CoordinatorOverviewResponse.PendingMentor::mentorId,
						CoordinatorOverviewResponse.PendingMentor::mentorName,
						CoordinatorOverviewResponse.PendingMentor::programName,
						CoordinatorOverviewResponse.PendingMentor::waitingDays)
				.containsExactly(
						tuple(unknownMentorId, "Mentor", "METU Stars 2026", 5),
						tuple(PENDING_MENTOR_ID, "Selin Ar", "METU Stars 2026", 0));
	}

	@Test
	void onlyActiveMentorshipsAreRatedAndOneWithNoMeetingOrStartDateCountsAsFresh() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 1)), List.of(), List.of());
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.PLANNED, today.minusDays(40)),
						mentorship(LUMEN_ID, MentorshipStatus.ACTIVE, null)),
				List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var detail = service.detail(COORDINATOR_ID, PROGRAM_ID);

		assertThat(detail.health())
				.isEqualTo(new CoordinatorProgramResponse.Health(1, 0, 0, List.of()));
		assertThat(detail.program().activeMentorshipCount()).isEqualTo(2);
		assertThat(detail.teams()).isEqualTo(new CoordinatorProgramResponse.TeamDistribution(2, 0));
	}

	@Test
	void cancelledAndFutureMeetingsAreNotContactAndTheLatestHeldOneIs() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 1)), List.of(), List.of());
		Meeting upcoming = scheduled(TERRA_ID, Instant.now().plus(2, ChronoUnit.DAYS));
		Meeting cancelled = held(TERRA_ID, 1);
		cancelled.setStatus(MeetingStatus.CANCELLED);
		Meeting latest = held(LUMEN_ID, 3);
		latest.setNotes("Görüşüldü.");
		Meeting older = held(LUMEN_ID, 30);
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40)),
						mentorship(LUMEN_ID, MentorshipStatus.ACTIVE, today.minusDays(40))),
				List.of(upcoming, cancelled, latest, older),
				List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var health = service.detail(COORDINATOR_ID, PROGRAM_ID).health();

		assertThat(health.risky()).isEqualTo(1);
		assertThat(health.healthy()).isEqualTo(1);
		assertThat(health.followUp()).isZero();
	}

	@Test
	void acceptedMentorsAreSplitIntoIdleWithRoomAndFullAndPendingOnesAreCountedApart() {
		stubManagedProgram(program(COORDINATOR_ID));
		UUID idleId = UUID.randomUUID();
		UUID roomyId = UUID.randomUUID();
		UUID fullAtDefaultId = UUID.randomUUID();
		UUID fullAtOwnLimitId = UUID.randomUUID();
		stubProgramRows(List.of(),
				List.of(membership(idleId, ProgramMentorStatus.ACCEPTED),
						membership(roomyId, ProgramMentorStatus.ACCEPTED),
						membership(fullAtDefaultId, ProgramMentorStatus.ACCEPTED),
						membership(fullAtOwnLimitId, ProgramMentorStatus.ACCEPTED),
						membership(PENDING_MENTOR_ID, ProgramMentorStatus.PENDING)),
				List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());
		when(mentorshipRepository.countOrganizationsByMentor(anyCollection(), anyCollection()))
				.thenReturn(List.of(count(roomyId, 3), count(fullAtDefaultId, 4), count(fullAtOwnLimitId, 1)));
		MentorProfile withoutOwnLimit = new MentorProfile();
		withoutOwnLimit.setId(fullAtDefaultId);
		MentorProfile withOwnLimit = new MentorProfile();
		withOwnLimit.setId(fullAtOwnLimitId);
		withOwnLimit.setMenteeCapacity(1);
		when(mentorProfileRepository.findAllById(any())).thenReturn(List.of(withoutOwnLimit, withOwnLimit));

		var mentors = service.detail(COORDINATOR_ID, PROGRAM_ID).mentors();

		assertThat(mentors).isEqualTo(new CoordinatorProgramResponse.MentorDistribution(1, 2, 1, 1));
	}

	@Test
	void theDetailShowsTheFiveLongestWaitingTeamsAndLeavesOutAStartupAwaitingApproval() {
		stubManagedProgram(program(COORDINATOR_ID));
		List<UUID> teamIds = IntStream.range(0, 7).mapToObj(index -> UUID.randomUUID()).toList();
		List<ProgramStartup> startups = IntStream.range(0, 7)
				.mapToObj(index -> startup(teamIds.get(index), index + 1))
				.toList();
		List<Organization> organizations = new ArrayList<>(IntStream.range(0, 7)
				.mapToObj(index -> organization(teamIds.get(index), "Takım " + (index + 1)))
				.toList());
		organizations.getLast().setApprovalStatus(UserAccountStatus.PENDING);
		stubProgramRows(startups, List.of(), List.of());
		stubTeamData(organizations, List.of(), List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var detail = service.detail(COORDINATOR_ID, PROGRAM_ID);

		assertThat(detail.awaitingTeams())
				.extracting(CoordinatorProgramResponse.AwaitingTeam::name,
						CoordinatorProgramResponse.AwaitingTeam::waitingDays)
				.containsExactly(tuple("Takım 6", 6), tuple("Takım 5", 5), tuple("Takım 4", 4), tuple("Takım 3", 3),
						tuple("Takım 2", 2));
		assertThat(detail.teams()).isEqualTo(new CoordinatorProgramResponse.TeamDistribution(0, 6));
		assertThat(detail.program().teamCount()).isEqualTo(6);
		assertThat(detail.program().oldestAwaitingMatchDays()).isEqualTo(6);
	}

	@Test
	void theDetailListsTheNextSixEvents() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(List.of(), List.of(), List.of());
		Instant from = Instant.now();
		List<ProgramEvent> events = IntStream.rangeClosed(1, 8)
				.mapToObj(day -> event("Etkinlik " + day, ProgramEventKind.EVENT, from.plus(day, ChronoUnit.DAYS)))
				.toList();
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(events);

		var upcoming = service.detail(COORDINATOR_ID, PROGRAM_ID).upcomingEvents();

		assertThat(upcoming).extracting(ProgramEventResponse::title)
				.containsExactly("Etkinlik 1", "Etkinlik 2", "Etkinlik 3", "Etkinlik 4", "Etkinlik 5", "Etkinlik 6");
	}

	@Test
	void theThreeTopicsBlockingTheMostTeamsAreListedFirst() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 1), startup(KAVUN_ID, 1)), List.of(),
				List.of());
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen"),
						organization(KAVUN_ID, "Kavun")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40)),
						mentorship(LUMEN_ID, MentorshipStatus.ACTIVE, today.minusDays(40)),
						mentorship(KAVUN_ID, MentorshipStatus.ACTIVE, today.minusDays(1))),
				List.of(),
				List.of(bottleneck(TERRA_ID, "Hukuk"), bottleneck(TERRA_ID, "Satış"), bottleneck(TERRA_ID, "Finansman"),
						bottleneck(TERRA_ID, "Ekip"), bottleneck(LUMEN_ID, "Satış"), bottleneck(LUMEN_ID, "Finansman"),
						bottleneck(KAVUN_ID, "Finansman")),
				List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var health = service.detail(COORDINATOR_ID, PROGRAM_ID).health();

		// Kavun met its mentor yesterday; its open bottleneck alone puts it on follow-up.
		assertThat(health).extracting(CoordinatorProgramResponse.Health::risky,
				CoordinatorProgramResponse.Health::followUp, CoordinatorProgramResponse.Health::healthy)
				.containsExactly(2, 1, 0);
		assertThat(health.riskyTopics()).hasSize(3);
		assertThat(health.riskyTopics().subList(0, 2))
				.containsExactly(new CoordinatorProgramResponse.TopicCount("Finansman", 3),
						new CoordinatorProgramResponse.TopicCount("Satış", 2));
		assertThat(health.riskyTopics().getLast().teams()).isEqualTo(1);
	}

	@Test
	void aTeamThatLeftStillCountsTheMentorshipItMadeInTheProgram() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		Program program = program(COORDINATOR_ID);
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID)).thenReturn(List.of(program));
		stubProgramRows(List.of(), List.of(), List.of());
		when(mentorshipRepository.findTeamsWithMentorshipIn(List.of(PROGRAM_ID)))
				.thenReturn(List.of(programTeam(PROGRAM_ID, TERRA_ID)));
		Mentorship madeHere = mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40));
		madeHere.setProgram(program);
		Mentorship madeElsewhere = mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40));
		Program other = program(OTHER_COORDINATOR_ID);
		other.setId(UUID.randomUUID());
		madeElsewhere.setProgram(other);
		Mentorship withoutProgram = mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40));
		stubTeamData(List.of(organization(TERRA_ID, "Terra")), List.of(madeHere, madeElsewhere, withoutProgram),
				List.of(), List.of(), List.of());

		var summary = service.list(COORDINATOR_ID).getFirst();

		assertThat(summary.teamCount()).isZero();
		assertThat(summary.activeMentorshipCount()).isEqualTo(1);
		assertThat(summary.riskyMentorshipCount()).isEqualTo(1);
		assertThat(summary.awaitingMatchCount()).isZero();
	}

	@Test
	void aProgramWhoseCreatorsAccountIsGoneStillListsUnderItsCreatorWithoutAName() {
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN));
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(program(COORDINATOR_ID)));
		when(appUserRepository.findAllById(any())).thenReturn(List.of());
		when(programPhaseRepository.findByProgramIdInOrderByPositionAsc(anyList())).thenReturn(List.of());
		when(programStartupRepository.findByProgramIdIn(anyList())).thenReturn(List.of());
		when(programMentorRepository.findByProgramIdIn(anyList())).thenReturn(List.of());

		var summary = service.list(ADMIN_ID).getFirst();

		assertThat(summary.coordinatorId()).isEqualTo(COORDINATOR_ID);
		assertThat(summary.coordinatorName()).isNull();
		assertThat(summary.oldestAwaitingMatchDays()).isNull();
		assertThat(summary.currentPhase()).isNull();
	}

	@Test
	void theCreatorComesFirstAsOwnerAndAnAccountThatIsGoneOrNoCoordinatorIsLeftOut() {
		Program program = program(COORDINATOR_ID);
		UUID goneId = UUID.randomUUID();
		UUID formerCoordinatorId = UUID.randomUUID();
		when(programCoordinatorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(
				coordinatorRow(goneId), coordinatorRow(formerCoordinatorId), coordinatorRow(OTHER_COORDINATOR_ID)));
		AppUser added = user(OTHER_COORDINATOR_ID, UserRole.COORDINATOR);
		added.setFullName("Mert Kaya");
		when(appUserRepository.findAllById(any())).thenReturn(List.of(
				added, user(formerCoordinatorId, UserRole.MENTOR), user(COORDINATOR_ID, UserRole.COORDINATOR)));

		assertThat(service.coordinatorsOf(program)).containsExactly(
				new CoordinatorProgramResponse.CoordinatorRef(COORDINATOR_ID, "Elif Demir", true),
				new CoordinatorProgramResponse.CoordinatorRef(OTHER_COORDINATOR_ID, "Mert Kaya", false));
	}

	@Test
	void theHomeCoversRunningProgramsUnlessOneProgramIsAskedFor() {
		Program running = program(COORDINATOR_ID);
		Program draft = program(COORDINATOR_ID);
		draft.setId(UUID.randomUUID());
		draft.setStatus(ProgramStatus.DRAFT);
		Program archived = program(COORDINATOR_ID);
		archived.setId(UUID.randomUUID());
		archived.setStatus(ProgramStatus.COMPLETED);
		List<Program> managed = List.of(running, draft, archived);

		assertThat(service.scopeOf(COORDINATOR_ID, managed, null)).containsExactly(running, draft);

		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findById(archived.getId())).thenReturn(Optional.of(archived));
		assertThat(service.scopeOf(COORDINATOR_ID, managed, archived.getId())).containsExactly(archived);
	}

	@Test
	void aProgramThatDoesNotExistIsNotFound() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.detail(COORDINATOR_ID, PROGRAM_ID))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(PROGRAM_ID.toString());
	}

	private void stubCoordinatorHome(Program... programs) {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID)).thenReturn(List.of(programs));
	}

	private void stubManagedProgram(Program program) {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
	}

	private void stubProgramRows(List<ProgramStartup> startups, List<ProgramMentor> mentors,
			List<ProgramPhase> phases) {
		when(appUserRepository.findAllById(any())).thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR)));
		when(programPhaseRepository.findByProgramIdInOrderByPositionAsc(anyList())).thenReturn(phases);
		when(programStartupRepository.findByProgramIdIn(anyList())).thenReturn(startups);
		when(programMentorRepository.findByProgramIdIn(anyList())).thenReturn(mentors);
	}

	private void stubTeamData(
			List<Organization> organizations,
			List<Mentorship> mentorships,
			List<Meeting> meetings,
			List<Bottleneck> bottlenecks,
			List<ActionItem> overdue) {
		when(organizationRepository.findAllById(any())).thenReturn(organizations);
		when(mentorshipRepository.findByOrganizationIdIn(anyCollection())).thenReturn(mentorships);
		when(meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(anyCollection())).thenReturn(meetings);
		when(bottleneckRepository.findByOrganizationIdInAndStatusIn(anyCollection(), anyCollection()))
				.thenReturn(bottlenecks);
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBefore(
				anyCollection(), anyCollection(), any()))
				.thenReturn(overdue);
	}

	private Program program(UUID coordinatorId) {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setCreatedBy(coordinatorId);
		program.setCreatedAt(Instant.parse("2026-02-01T09:00:00Z"));
		return program;
	}

	private ProgramStartup startup(UUID organizationId, int daysAgo) {
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(PROGRAM_ID);
		startup.setOrganizationId(organizationId);
		startup.setCreatedAt(today.minusDays(daysAgo).atTime(12, 0).atZone(MeetingSlotTimes.APP_ZONE).toInstant());
		return startup;
	}

	private static ProgramMentor membership(UUID mentorId, ProgramMentorStatus status) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(PROGRAM_ID);
		membership.setMentorId(mentorId);
		membership.setStatus(status);
		return membership;
	}

	private static ProgramPhase phase(String name, LocalDate start, LocalDate end) {
		ProgramPhase phase = new ProgramPhase();
		phase.setId(UUID.randomUUID());
		phase.setProgramId(PROGRAM_ID);
		phase.setName(name);
		phase.setStartDate(start);
		phase.setEndDate(end);
		return phase;
	}

	private static Organization organization(UUID id, String name) {
		Organization organization = new Organization();
		organization.setId(id);
		organization.setName(name);
		return organization;
	}

	private static Mentorship mentorship(UUID organizationId, MentorshipStatus status, LocalDate startDate) {
		Mentorship mentorship = new Mentorship();
		mentorship.setId(UUID.randomUUID());
		mentorship.setMentor(user(MENTOR_ID, UserRole.MENTOR));
		mentorship.setOrganization(organization(organizationId, "Takım"));
		mentorship.setStatus(status);
		mentorship.setStartDate(startDate);
		return mentorship;
	}

	private static Meeting meeting(UUID organizationId, int daysAgo) {
		Meeting meeting = new Meeting();
		meeting.setMentor(user(MENTOR_ID, UserRole.MENTOR));
		meeting.setOrganization(organization(organizationId, "Takım"));
		meeting.setStatus(MeetingStatus.COMPLETED);
		meeting.setScheduledAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
		return meeting;
	}

	private static Bottleneck bottleneck(UUID organizationId, String title) {
		Bottleneck bottleneck = new Bottleneck();
		bottleneck.setOrganization(organization(organizationId, "Takım"));
		bottleneck.setTitle(title);
		bottleneck.setStatus(BottleneckStatus.OPEN);
		return bottleneck;
	}

	private static ActionItem actionItem(UUID organizationId) {
		ActionItem item = new ActionItem();
		item.setOrganization(organization(organizationId, "Takım"));
		return item;
	}

	private static Meeting scheduled(UUID organizationId, Instant at) {
		Meeting meeting = meeting(organizationId, 0);
		meeting.setId(UUID.randomUUID());
		meeting.setStatus(MeetingStatus.SCHEDULED);
		meeting.setScheduledAt(at);
		return meeting;
	}

	private static Meeting held(UUID organizationId, int daysAgo) {
		Meeting meeting = meeting(organizationId, daysAgo);
		meeting.setId(UUID.randomUUID());
		return meeting;
	}

	private static MeetingNote liveNote(Meeting meeting, String content) {
		MeetingNote note = new MeetingNote();
		note.setMeeting(meeting);
		note.setContent(content);
		return note;
	}

	private static ProgramEvent event(String title, ProgramEventKind kind, Instant startsAt) {
		ProgramEvent event = new ProgramEvent();
		event.setId(UUID.randomUUID());
		event.setProgramId(PROGRAM_ID);
		event.setTitle(title);
		event.setKind(kind);
		event.setStartsAt(startsAt);
		return event;
	}

	private static StoredDocument document(UUID id, UUID ownerId, DocumentKind kind) {
		StoredDocument document = new StoredDocument();
		document.setId(id);
		document.setUserId(ownerId);
		document.setKind(kind);
		return document;
	}

	private static MentorshipRequest pendingRequest(UUID mentorId, Instant createdAt) {
		MentorshipRequest request = new MentorshipRequest();
		request.setMentor(user(mentorId, UserRole.MENTOR));
		request.setCreatedAt(createdAt);
		return request;
	}

	private static ProgramCoordinator coordinatorRow(UUID userId) {
		ProgramCoordinator row = new ProgramCoordinator();
		row.setProgramId(PROGRAM_ID);
		row.setUserId(userId);
		return row;
	}

	private static MentorshipRepository.ProgramTeam programTeam(UUID programId, UUID organizationId) {
		return new MentorshipRepository.ProgramTeam() {
			@Override
			public UUID getProgramId() {
				return programId;
			}

			@Override
			public UUID getOrganizationId() {
				return organizationId;
			}
		};
	}

	private static MentorCount count(UUID mentorId, long total) {
		return new MentorCount() {
			@Override
			public UUID getMentorId() {
				return mentorId;
			}

			@Override
			public Long getTotal() {
				return total;
			}
		};
	}

	private static AppUser user(UUID id, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName(role == UserRole.COORDINATOR ? "Elif Demir" : "Kullanıcı");
		user.setEmail(id + "@tekno.test");
		user.setRole(role);
		return user;
	}
}
