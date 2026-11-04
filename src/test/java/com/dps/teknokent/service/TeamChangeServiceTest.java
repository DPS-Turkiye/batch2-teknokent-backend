package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.dps.teknokent.domain.TeamChangeKind;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.TeamChangeRequest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamChangeServiceTest {

	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
	private static final UUID LEADER_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
	private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
	private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");
	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private TeamChangeRequestRepository teamChangeRequestRepository;
	@Mock
	private TeamMembershipService teamMembershipService;
	@Mock
	private TaskHandoverService taskHandoverService;
	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private TeamChangeService service;

	private Organization startup;
	private AppUser leader;
	private AppUser member;
	private AppUser other;
	private AppUser coordinator;

	@BeforeEach
	void setUp() {
		startup = new Organization();
		startup.setId(ORGANIZATION_ID);
		startup.setName("Pazar Lab");
		startup.setActiveProgramId(PROGRAM_ID);
		startup.setInviteCode("8U5TNWH3");
		leader = person(LEADER_ID, "Ceren Aksoy");
		member = person(MEMBER_ID, "Deniz Yıldız");
		other = person(OTHER_ID, "Ece Kurt");
		startup.setFounder(leader);
		coordinator = person(COORDINATOR_ID, "Arya Koordinatör");
		coordinator.setOrganization(null);
		coordinator.setRole(UserRole.COORDINATOR);

		when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(startup);
		for (AppUser user : List.of(leader, member, other, coordinator)) {
			when(domainLookupService.requireUser(user.getId())).thenReturn(user);
		}
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setCreatedBy(COORDINATOR_ID);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		when(appUserRepository.findAllById(List.of(COORDINATOR_ID))).thenReturn(List.of(coordinator));
		when(teamChangeRequestRepository.saveAndFlush(any(TeamChangeRequest.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void requestLeave_waitsForTheCoordinatorAndTellsThem() {
		var result = service.requestLeave(MEMBER_ID, ORGANIZATION_ID);

		assertThat(result.kind()).isEqualTo(TeamChangeKind.LEAVE);
		assertThat(result.status()).isEqualTo(TeamChangeStatus.PENDING);
		assertThat(member.getOrganization()).isSameAs(startup);
		verify(teamChangeRequestRepository).saveAndFlush(argThat(request -> request.getProgramId().equals(PROGRAM_ID)
				&& request.getRequestedBy() == member && request.getMember() == member));
		verify(notificationService).create(eq(coordinator), anyString(), anyString(),
				eq("/coordinator/programs/" + PROGRAM_ID + "/approvals"));
	}

	@Test
	void requestLeave_turnsTheLeaderToTheSuccessorFlow() {
		assertThatThrownBy(() -> service.requestLeave(LEADER_ID, ORGANIZATION_ID))
				.isInstanceOf(InvalidRequestException.class);
		verify(teamChangeRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void requestLeave_refusesASecondWaitingRequest() {
		when(teamChangeRequestRepository.existsByMemberIdAndOrganizationIdAndStatus(MEMBER_ID, ORGANIZATION_ID,
				TeamChangeStatus.PENDING)).thenReturn(true);

		assertThatThrownBy(() -> service.requestLeave(MEMBER_ID, ORGANIZATION_ID))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void requestLeave_refusesSomeoneOutsideTheTeam() {
		AppUser stranger = person(UUID.randomUUID(), "Yabancı");
		stranger.setOrganization(null);
		when(domainLookupService.requireUser(stranger.getId())).thenReturn(stranger);

		assertThatThrownBy(() -> service.requestLeave(stranger.getId(), ORGANIZATION_ID))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void requestRemoval_isTheLeadersAndTellsTheMember() {
		var result = service.requestRemoval(LEADER_ID, ORGANIZATION_ID, MEMBER_ID);

		assertThat(result.kind()).isEqualTo(TeamChangeKind.REMOVE);
		assertThat(result.requestedById()).isEqualTo(LEADER_ID);
		verify(notificationService).create(eq(member), anyString(), anyString(), eq("/startup/team"));
		verify(notificationService).create(eq(coordinator), anyString(), anyString(), anyString());
	}

	@Test
	void requestRemoval_refusesAMemberWhoIsNotTheLeader() {
		assertThatThrownBy(() -> service.requestRemoval(OTHER_ID, ORGANIZATION_ID, MEMBER_ID))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void cancel_withdrawsOnlyTheRequestersOwnWaitingRequest() {
		TeamChangeRequest request = pending(TeamChangeKind.LEAVE, member);
		when(teamChangeRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.cancel(LEADER_ID, request.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		var result = service.cancel(MEMBER_ID, request.getId());

		assertThat(result.status()).isEqualTo(TeamChangeStatus.CANCELLED);
	}

	@Test
	void leaveAsLeader_handsTheTeamToTheSuccessorAtOnce() {
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(ORGANIZATION_ID))
				.thenReturn(List.of(leader, member, other));

		service.leaveAsLeader(LEADER_ID, ORGANIZATION_ID, MEMBER_ID);

		assertThat(startup.getFounder()).isSameAs(member);
		assertThat(startup.getArchivedAt()).isNull();
		assertThat(leader.getOrganization()).isNull();
		assertThat(leader.getProgramId()).isEqualTo(PROGRAM_ID);
		verify(teamMembershipService).left(leader, startup, TeamExitReason.LEFT);
		verify(taskHandoverService).markLeft(leader, startup);
		verify(notificationService).create(eq(member), anyString(), anyString(), eq("/startup/team"));
	}

	@Test
	void leaveAsLeader_needsASuccessorFromTheTeam() {
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(ORGANIZATION_ID))
				.thenReturn(List.of(leader, member));

		assertThatThrownBy(() -> service.leaveAsLeader(LEADER_ID, ORGANIZATION_ID, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.leaveAsLeader(LEADER_ID, ORGANIZATION_ID, OTHER_ID))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(leader.getOrganization()).isSameAs(startup);
	}

	@Test
	void leaveAsLeader_cannotPickSomeoneWaitingToLeave() {
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(ORGANIZATION_ID))
				.thenReturn(List.of(leader, member));
		when(teamChangeRequestRepository.existsByMemberIdAndOrganizationIdAndStatus(MEMBER_ID, ORGANIZATION_ID,
				TeamChangeStatus.PENDING)).thenReturn(true);

		assertThatThrownBy(() -> service.leaveAsLeader(LEADER_ID, ORGANIZATION_ID, MEMBER_ID))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void leaveAsLeader_archivesTheStartupWhenNobodyElseIsInIt() {
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(ORGANIZATION_ID)).thenReturn(List.of(leader));

		service.leaveAsLeader(LEADER_ID, ORGANIZATION_ID, null);

		assertThat(startup.getArchivedAt()).isNotNull();
		assertThat(startup.getInviteCode()).isNull();
		assertThat(startup.isApprovedForProgram()).isFalse();
		assertThat(leader.getOrganization()).isNull();
		verify(notificationService).create(eq(coordinator), anyString(), anyString(), anyString());
	}

	@Test
	void decide_approvedLeaveTakesTheMemberOutAndHandsOverTheirTasks() {
		TeamChangeRequest request = pending(TeamChangeKind.LEAVE, member);
		when(teamChangeRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		service.decide(COORDINATOR_ID, PROGRAM_ID, request.getId(), true);

		assertThat(request.getStatus()).isEqualTo(TeamChangeStatus.APPROVED);
		assertThat(request.getDecidedBy()).isSameAs(coordinator);
		assertThat(member.getOrganization()).isNull();
		verify(teamMembershipService).left(member, startup, TeamExitReason.LEFT);
		verify(taskHandoverService).markLeft(member, startup);
		verify(notificationService).create(eq(member), anyString(), anyString(), eq("/startup"));
		verify(notificationService).create(eq(leader), anyString(), anyString(), eq("/startup/team"));
	}

	@Test
	void decide_approvedRemovalIsRecordedAsRemoved() {
		TeamChangeRequest request = pending(TeamChangeKind.REMOVE, leader);
		when(teamChangeRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		service.decide(COORDINATOR_ID, PROGRAM_ID, request.getId(), true);

		verify(teamMembershipService).left(member, startup, TeamExitReason.REMOVED);
	}

	@Test
	void decide_rejectionKeepsTheMemberAndTellsTheRequester() {
		TeamChangeRequest request = pending(TeamChangeKind.REMOVE, leader);
		when(teamChangeRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		service.decide(COORDINATOR_ID, PROGRAM_ID, request.getId(), false);

		assertThat(request.getStatus()).isEqualTo(TeamChangeStatus.REJECTED);
		assertThat(member.getOrganization()).isSameAs(startup);
		verify(notificationService).create(eq(leader), anyString(), anyString(), eq("/startup/team"));
		verify(notificationService).create(eq(member), anyString(), anyString(), eq("/startup/team"));
		verify(taskHandoverService, never()).markLeft(any(), any());
	}

	@Test
	void decide_cancelsARequestWhoseMemberIsAlreadyGone() {
		TeamChangeRequest request = pending(TeamChangeKind.LEAVE, member);
		member.setOrganization(null);
		when(teamChangeRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		service.decide(COORDINATOR_ID, PROGRAM_ID, request.getId(), true);

		assertThat(request.getStatus()).isEqualTo(TeamChangeStatus.CANCELLED);
		verify(teamMembershipService, never()).left(any(), any(), any());
	}

	@Test
	void decide_ignoresARequestOfAnotherProgram() {
		TeamChangeRequest request = pending(TeamChangeKind.LEAVE, member);
		when(teamChangeRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.decide(COORDINATOR_ID, UUID.randomUUID(), request.getId(), true))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void removeOutsideProgram_isRefusedForAProgramStartup() {
		assertThatThrownBy(() -> service.removeOutsideProgram(LEADER_ID, ORGANIZATION_ID, MEMBER_ID))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(member.getOrganization()).isSameAs(startup);
	}

	@Test
	void removeOutsideProgram_removesAtOnceWithoutAProgram() {
		startup.setActiveProgramId(null);

		service.removeOutsideProgram(LEADER_ID, ORGANIZATION_ID, MEMBER_ID);

		assertThat(member.getOrganization()).isNull();
		verify(teamMembershipService).left(member, startup, TeamExitReason.REMOVED);
	}

	private TeamChangeRequest pending(TeamChangeKind kind, AppUser requester) {
		TeamChangeRequest request = new TeamChangeRequest();
		request.setId(UUID.randomUUID());
		request.setOrganization(startup);
		request.setProgramId(PROGRAM_ID);
		request.setMember(member);
		request.setRequestedBy(requester);
		request.setKind(kind);
		return request;
	}

	private AppUser person(UUID id, String name) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName(name);
		user.setEmail(id + "@pazar.lab");
		user.setRole(UserRole.MENTEE);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setOrganization(startup);
		return user;
	}
}
