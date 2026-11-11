package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamMembership;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamMembershipRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonAccessServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private TeamMembershipRepository teamMembershipRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private AppUserRepository appUserRepository;

	@InjectMocks
	private PersonAccessService service;

	private final List<Program> programs = new ArrayList<>();
	private final List<ProgramStartup> programStartups = new ArrayList<>();
	private final List<ProgramMentor> pools = new ArrayList<>();
	private final List<TeamMembership> stays = new ArrayList<>();
	private final List<AppUser> users = new ArrayList<>();
	private final List<ProgramCoordinator> coordinators = new ArrayList<>();

	private Organization startup;
	private AppUser person;

	@BeforeEach
	void setUp() {
		startup = startup();
		person = user(UserRole.MENTEE, UserAccountStatus.APPROVED);
		person.setOrganization(startup);

		when(programRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
			Collection<?> ids = invocation.getArgument(0);
			return programs.stream().filter(program -> ids.contains(program.getId())).toList();
		});
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(any())).thenAnswer(invocation -> {
			UUID organizationId = invocation.getArgument(0);
			return programStartups.stream().filter(row -> row.getOrganizationId().equals(organizationId)).toList();
		});
		when(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(any())).thenAnswer(invocation -> {
			UUID mentorId = invocation.getArgument(0);
			return pools.stream().filter(row -> row.getMentorId().equals(mentorId)).toList();
		});
		when(appUserRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
			Collection<?> ids = invocation.getArgument(0);
			return users.stream().filter(user -> ids.contains(user.getId())).toList();
		});
		when(programCoordinatorRepository.findByProgramIdIn(anyCollection())).thenAnswer(invocation -> {
			Collection<?> ids = invocation.getArgument(0);
			return coordinators.stream().filter(row -> ids.contains(row.getProgramId())).toList();
		});
		when(programCoordinatorRepository.existsByProgramIdAndUserId(any(), any())).thenAnswer(invocation ->
				coordinators.stream().anyMatch(row -> row.getProgramId().equals(invocation.getArgument(0))
						&& row.getUserId().equals(invocation.getArgument(1))));
		when(teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(any())).thenAnswer(invocation -> {
			UUID userId = invocation.getArgument(0);
			return stays.stream().filter(stay -> stay.getUserId().equals(userId)).toList();
		});
	}

	@Test
	void theirOwnProfileIsAlwaysVisibleEvenWhilePending() {
		person.setStatus(UserAccountStatus.PENDING);

		assertThat(service.requireVisible(person.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void anApprovedAdminSeesAnyone() {
		AppUser admin = user(UserRole.ADMIN, UserAccountStatus.APPROVED);

		assertThat(service.requireVisible(admin.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void aPendingAdminGetsNotFound() {
		assertHidden(user(UserRole.ADMIN, UserAccountStatus.PENDING), person);
	}

	@Test
	void anUnrelatedMenteeGetsNotFound() {
		AppUser stranger = user(UserRole.MENTEE, UserAccountStatus.APPROVED);
		stranger.setOrganization(startup());

		assertHidden(stranger, person);
	}

	@Test
	void anUnrelatedMentorGetsNotFound() {
		assertHidden(user(UserRole.MENTOR, UserAccountStatus.APPROVED), person);
	}

	@Test
	void aTeammateSeesThem() {
		AppUser teammate = user(UserRole.MENTEE, UserAccountStatus.APPROVED);
		teammate.setOrganization(startup);

		assertThat(service.requireVisible(teammate.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void aPersonalTeamIsNotAStartupToShare() {
		startup.setPersonal(true);
		AppUser other = user(UserRole.MENTEE, UserAccountStatus.APPROVED);
		other.setOrganization(startup);

		assertHidden(other, person);
	}

	@Test
	void aPendingTeammateGetsNotFound() {
		AppUser teammate = user(UserRole.MENTEE, UserAccountStatus.PENDING);
		teammate.setOrganization(startup);

		assertHidden(teammate, person);
	}

	@Test
	void aMentorTheStartupSentARequestToSeesThem() {
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(mentorshipRequestRepository.existsByMentorIdAndOrganizationIdAndStatusIn(eq(mentor.getId()),
				eq(startup.getId()), anyCollection())).thenReturn(true);

		assertThat(service.requireVisible(mentor.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void theStartupsMentorSeesThem() {
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(mentorshipRepository.existsByMentorIdAndOrganizationId(mentor.getId(), startup.getId()))
				.thenReturn(true);

		assertThat(service.requireVisible(mentor.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void theMenteeSeesTheirStartupsMentor() {
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(mentorshipRepository.existsByMentorIdAndOrganizationId(mentor.getId(), startup.getId()))
				.thenReturn(true);

		assertThat(service.requireVisible(person.getId(), mentor.getId())).isSameAs(mentor);
	}

	@Test
	void aRequestOnlyCountsWhenTheOtherSideIsAMentor() {
		AppUser other = user(UserRole.MENTEE, UserAccountStatus.APPROVED);
		other.setOrganization(startup());
		when(mentorshipRequestRepository.existsByMentorIdAndOrganizationIdAndStatusIn(eq(other.getId()),
				eq(startup.getId()), anyCollection())).thenReturn(true);

		assertHidden(other, person);
	}

	@Test
	void aMenteeInTheSameProgramSeesThem() {
		Program program = program(ProgramType.STARTUP_MENTORING, UUID.randomUUID());
		linkStartup(program, startup);
		AppUser other = user(UserRole.MENTEE, UserAccountStatus.APPROVED);
		Organization otherStartup = startup();
		other.setOrganization(otherStartup);
		linkStartup(program, otherStartup);

		assertThat(service.requireVisible(other.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void anAcceptedPoolMentorOfTheirProgramSeesThem() {
		Program program = program(ProgramType.STARTUP_MENTORING, UUID.randomUUID());
		linkStartup(program, startup);
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		pool(program, mentor, ProgramMentorStatus.ACCEPTED, ProgramMentorInvitedBy.COORDINATOR);

		assertThat(service.requireVisible(mentor.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void anInvitedPoolMentorDoesNotShareTheProgram() {
		Program program = program(ProgramType.STARTUP_MENTORING, UUID.randomUUID());
		linkStartup(program, startup);
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		pool(program, mentor, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR);

		assertHidden(mentor, person);
	}

	@Test
	void theCoordinatorOfTheirStartupMentoringProgramSeesThem() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.STARTUP_MENTORING, coordinator.getId());
		linkStartup(program, startup);

		assertThat(service.requireVisible(coordinator.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void aCoordinatorAddedToTheProgramSeesThem() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.STARTUP_MENTORING, UUID.randomUUID());
		linkStartup(program, startup);
		addCoordinator(program, coordinator);

		assertThat(service.requireVisible(coordinator.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void aCoordinatorSeesSomeoneFromAPastTeamInTheirProgram() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Organization formerStartup = startup();
		Program program = program(ProgramType.STARTUP_MENTORING, coordinator.getId());
		linkStartup(program, formerStartup);
		TeamMembership stay = new TeamMembership();
		stay.setUserId(person.getId());
		stay.setOrganization(formerStartup);
		stays.add(stay);

		assertThat(service.requireVisible(coordinator.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void aCoordinatorSeesAMentorWhoAskedToJoinTheirPool() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.STARTUP_MENTORING, coordinator.getId());
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		pool(program, mentor, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR);

		assertThat(service.requireVisible(coordinator.getId(), mentor.getId())).isSameAs(mentor);
	}

	@Test
	void aCoordinatorsOwnInviteDoesNotOpenAMentor() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.STARTUP_MENTORING, coordinator.getId());
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		pool(program, mentor, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR);

		assertHidden(coordinator, mentor);
	}

	@Test
	void aCoordinatorOfAnotherProgramGetsNotFound() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.STARTUP_MENTORING, UUID.randomUUID());
		linkStartup(program, startup);
		program(ProgramType.STARTUP_MENTORING, coordinator.getId());

		assertHidden(coordinator, person);
	}

	@Test
	void coordinatingAProgramOfAnotherTypeDoesNotCount() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.PEER_MENTORING, coordinator.getId());
		person.setOrganization(null);
		person.setProgramId(program.getId());

		assertHidden(coordinator, person);
	}

	@Test
	void aPendingCoordinatorGetsNotFound() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.PENDING);
		Program program = program(ProgramType.STARTUP_MENTORING, coordinator.getId());
		linkStartup(program, startup);

		assertHidden(coordinator, person);
	}

	@Test
	void aCoordinatorCannotOpenAnotherStaffMember() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.STARTUP_MENTORING, coordinator.getId());
		linkStartup(program, startup);
		person.setRole(UserRole.ADMIN);

		assertHidden(coordinator, person);
	}

	@Test
	void aMemberSeesTheCoordinatorOfTheirProgram() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.STARTUP_MENTORING, coordinator.getId());
		linkStartup(program, startup);

		assertThat(service.requireVisible(person.getId(), coordinator.getId())).isSameAs(coordinator);
	}

	@Test
	void aMemberCannotOpenTheCoordinatorOfAnotherProgram() {
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		program(ProgramType.STARTUP_MENTORING, coordinator.getId());

		assertHidden(person, coordinator);
	}

	@Test
	void anUnknownPersonIsNotFound() {
		AppUser admin = user(UserRole.ADMIN, UserAccountStatus.APPROVED);
		UUID missing = UUID.randomUUID();
		when(domainLookupService.requireUser(missing)).thenThrow(new ResourceNotFoundException("User not found"));

		assertThatThrownBy(() -> service.requireVisible(admin.getId(), missing))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void onlyAnApprovedAdminMayListAccounts() {
		AppUser admin = user(UserRole.ADMIN, UserAccountStatus.APPROVED);
		AppUser pendingAdmin = user(UserRole.ADMIN, UserAccountStatus.PENDING);
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);

		service.requireAdmin(admin.getId());
		for (AppUser refused : List.of(pendingAdmin, coordinator, person)) {
			assertThatThrownBy(() -> service.requireAdmin(refused.getId()))
					.isInstanceOfSatisfying(ResponseStatusException.class,
							exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		}
	}

	@Test
	void aRejectedActorGetsNotFound() {
		AppUser teammate = user(UserRole.MENTEE, UserAccountStatus.REJECTED);
		teammate.setOrganization(startup);

		assertHidden(teammate, person);
	}

	@Test
	void sharingTheirOwnProgramCounts() {
		Program program = program(ProgramType.PEER_MENTORING, UUID.randomUUID());
		person.setOrganization(null);
		person.setProgramId(program.getId());
		AppUser other = user(UserRole.MENTEE, UserAccountStatus.APPROVED);
		other.setProgramId(program.getId());

		assertThat(service.requireVisible(other.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void twoUnrelatedMentorsCannotOpenEachOther() {
		AppUser first = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		AppUser second = user(UserRole.MENTOR, UserAccountStatus.APPROVED);

		assertHidden(first, second);
	}

	@Test
	void aDeclinedOrWithdrawnRequestDoesNotCount() {
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(mentorshipRequestRepository.existsByMentorIdAndOrganizationIdAndStatusIn(eq(mentor.getId()),
				eq(startup.getId()), anyCollection())).thenAnswer(invocation -> {
					Collection<?> statuses = invocation.getArgument(2);
					return statuses.contains(MentorshipRequestStatus.DECLINED)
							|| statuses.contains(MentorshipRequestStatus.WITHDRAWN);
				});

		assertHidden(mentor, person);
	}

	@Test
	void anOpenRequestCounts() {
		AppUser mentor = user(UserRole.MENTOR, UserAccountStatus.APPROVED);
		when(mentorshipRequestRepository.existsByMentorIdAndOrganizationIdAndStatusIn(eq(mentor.getId()),
				eq(startup.getId()), anyCollection())).thenAnswer(invocation -> {
					Collection<?> statuses = invocation.getArgument(2);
					return statuses.contains(MentorshipRequestStatus.PENDING);
				});

		assertThat(service.requireVisible(mentor.getId(), person.getId())).isSameAs(person);
	}

	@Test
	void anAdminWhoCreatedAProgramIsNotOpenedToItsMembersOnceItHasCoordinators() {
		AppUser admin = user(UserRole.ADMIN, UserAccountStatus.APPROVED);
		AppUser coordinator = user(UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		Program program = program(ProgramType.STARTUP_MENTORING, admin.getId());
		linkStartup(program, startup);
		addCoordinator(program, coordinator);

		assertHidden(person, admin);
		assertThat(service.requireVisible(person.getId(), coordinator.getId())).isSameAs(coordinator);
	}

	@Test
	void onlyThePersonAndAdminsSeeContactDetails() {
		AppUser admin = user(UserRole.ADMIN, UserAccountStatus.APPROVED);
		AppUser pendingAdmin = user(UserRole.ADMIN, UserAccountStatus.PENDING);
		AppUser teammate = user(UserRole.MENTEE, UserAccountStatus.APPROVED);

		assertThat(service.seesContactDetails(person.getId(), person.getId())).isTrue();
		assertThat(service.seesContactDetails(admin.getId(), person.getId())).isTrue();
		assertThat(service.seesContactDetails(pendingAdmin.getId(), person.getId())).isFalse();
		assertThat(service.seesContactDetails(teammate.getId(), person.getId())).isFalse();
	}

	private void addCoordinator(Program program, AppUser coordinator) {
		ProgramCoordinator row = new ProgramCoordinator();
		row.setProgramId(program.getId());
		row.setUserId(coordinator.getId());
		coordinators.add(row);
	}

	private void assertHidden(AppUser actor, AppUser target) {
		assertThatThrownBy(() -> service.requireVisible(actor.getId(), target.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	private AppUser user(UserRole role, UserAccountStatus status) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName("Kişi " + role);
		user.setRole(role);
		user.setStatus(status);
		when(domainLookupService.requireUser(user.getId())).thenReturn(user);
		users.add(user);
		return user;
	}

	private static Organization startup() {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName("Örnek Girişim");
		organization.setApprovalStatus(UserAccountStatus.APPROVED);
		return organization;
	}

	private Program program(ProgramType type, UUID createdBy) {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setProgramType(type);
		program.setCreatedBy(createdBy);
		programs.add(program);
		return program;
	}

	private void linkStartup(Program program, Organization organization) {
		ProgramStartup link = new ProgramStartup();
		link.setProgramId(program.getId());
		link.setOrganizationId(organization.getId());
		programStartups.add(link);
	}

	private void pool(Program program, AppUser mentor, ProgramMentorStatus status, ProgramMentorInvitedBy invitedBy) {
		ProgramMentor row = new ProgramMentor();
		row.setProgramId(program.getId());
		row.setMentorId(mentor.getId());
		row.setStatus(status);
		row.setInvitedBy(invitedBy);
		pools.add(row);
	}
}
