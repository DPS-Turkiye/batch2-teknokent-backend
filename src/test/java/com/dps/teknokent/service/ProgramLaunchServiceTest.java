package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.TeamJoinStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateCoordinatorProgramRequest;
import com.dps.teknokent.dto.requests.TeamInviteInput;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
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
	@InjectMocks
	private ProgramLaunchService service;

	private static final UUID ELIF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID BURCU = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

	@Test
	void launchingSavesTheRulesTheCoordinatorsAndATeamCodePerTeam() {
		when(programService.requireCoordinatorOrAdmin(ELIF)).thenReturn(user(ELIF, UserRole.COORDINATOR));
		when(appUserRepository.findById(BURCU)).thenReturn(Optional.of(user(BURCU, UserRole.COORDINATOR)));

		service.create(ELIF, request(List.of(ELIF, BURCU), List.of(
				new TeamInviteInput("Kavşak", "Deniz@Kavsak.co"),
				new TeamInviteInput("Mira Bio", "selin@mirabio.com"))));

		ArgumentCaptor<Program> program = ArgumentCaptor.forClass(Program.class);
		verify(programRepository).save(program.capture());
		assertThat(program.getValue().getName()).isEqualTo("METU BİGG 2026");
		assertThat(program.getValue().getDefaultMentorCapacity()).isEqualTo(4);
		assertThat(program.getValue().getCommunicationGapDays()).isEqualTo(14);
		assertThat(program.getValue().getBottleneckFlagDays()).isEqualTo(21);
		ArgumentCaptor<ProgramCoordinator> coordinator = ArgumentCaptor.forClass(ProgramCoordinator.class);
		verify(programCoordinatorRepository).save(coordinator.capture());
		assertThat(coordinator.getValue().getUserId()).isEqualTo(BURCU);
		ArgumentCaptor<ProgramTeamInvite> invites = ArgumentCaptor.forClass(ProgramTeamInvite.class);
		verify(inviteRepository, org.mockito.Mockito.times(2)).save(invites.capture());
		assertThat(invites.getAllValues()).extracting(ProgramTeamInvite::getLeadEmail)
				.containsExactly("deniz@kavsak.co", "selin@mirabio.com");
		assertThat(invites.getAllValues()).allSatisfy(invite -> assertThat(invite.getCode()).startsWith("BIGG26-"));
		verify(programService).detail(any(), any());
	}

	@Test
	void twoTeamsCannotShareALeadEmailAndOnlyApprovedCoordinatorsCanBeAdded() {
		when(programService.requireCoordinatorOrAdmin(ELIF)).thenReturn(user(ELIF, UserRole.COORDINATOR));

		assertThatThrownBy(() -> service.create(ELIF, request(List.of(), List.of(
				new TeamInviteInput("A", "same@team.co"), new TeamInviteInput("B", "SAME@team.co")))))
				.isInstanceOf(InvalidRequestException.class);

		UUID mentor = UUID.randomUUID();
		when(appUserRepository.findById(mentor)).thenReturn(Optional.of(user(mentor, UserRole.MENTOR)));
		assertThatThrownBy(() -> service.create(ELIF, request(List.of(mentor), List.of())))
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

	private static CreateCoordinatorProgramRequest request(List<UUID> coordinators, List<TeamInviteInput> teams) {
		return new CreateCoordinatorProgramRequest(null, " METU BİGG 2026 ", "Kuluçka", LocalDate.of(2026, 11, 3),
				LocalDate.of(2027, 9, 30), coordinators, 4, 14, 14, 21, teams);
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
