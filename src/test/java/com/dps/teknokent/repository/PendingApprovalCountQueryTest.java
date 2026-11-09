package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.TeamChangeKind;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamChangeRequest;

import jakarta.persistence.EntityManager;

/** The counts behind the Onaylar tab's badge agree with the lists Onaylar shows (ProgramApprovalService). */
@DataJpaTest
class PendingApprovalCountQueryTest {

	private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
	private static final UUID PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID OTHER_PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

	@Autowired
	private AppUserRepository users;
	@Autowired
	private ProgramMentorRepository programMentors;
	@Autowired
	private OrganizationRepository organizations;
	@Autowired
	private TeamChangeRequestRepository teamChanges;
	@Autowired
	private ProgramStartupRepository programStartups;
	@Autowired
	private EntityManager entityManager;

	@Test
	void countsPendingStudentsOfTheProgramOnly() {
		user("pending-1@test", UserRole.MENTEE, UserAccountStatus.PENDING, PROGRAM);
		user("pending-2@test", UserRole.MENTEE, UserAccountStatus.PENDING, PROGRAM);
		user("approved@test", UserRole.MENTEE, UserAccountStatus.APPROVED, PROGRAM);
		user("pending-mentor@test", UserRole.MENTOR, UserAccountStatus.PENDING, PROGRAM);
		user("elsewhere@test", UserRole.MENTEE, UserAccountStatus.PENDING, OTHER_PROGRAM);
		AppUser alsoStudent = user("mentor-and-student@test", UserRole.MENTOR, UserAccountStatus.PENDING, PROGRAM);
		alsoStudent.grant(UserRole.MENTEE, PermissionSource.ADMIN, NOW);
		entityManager.flush();

		long counted = users.countByProgramIdAndPermissionAndStatus(PROGRAM, UserRole.MENTEE,
				UserAccountStatus.PENDING);
		assertThat(counted).isEqualTo(3);
		assertThat(counted).isEqualTo(users.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(PROGRAM,
				UserRole.MENTEE, UserAccountStatus.PENDING).size());
	}

	@Test
	void countsMentorsWhoAskedToJoinButNotTheOnesTheCoordinatorInvited() {
		membership(PROGRAM, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR);
		membership(PROGRAM, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.COORDINATOR);
		membership(PROGRAM, ProgramMentorStatus.ACCEPTED, ProgramMentorInvitedBy.MENTOR);
		membership(OTHER_PROGRAM, ProgramMentorStatus.PENDING, ProgramMentorInvitedBy.MENTOR);
		entityManager.flush();

		long counted = programMentors.countByProgramIdAndStatusAndInvitedBy(PROGRAM, ProgramMentorStatus.PENDING,
				ProgramMentorInvitedBy.MENTOR);
		assertThat(counted).isEqualTo(1);
		assertThat(counted).isEqualTo(programMentors.findByProgramIdOrderByCreatedAtAsc(PROGRAM).stream()
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.PENDING
						&& membership.getInvitedBy() == ProgramMentorInvitedBy.MENTOR)
				.count());
	}

	@Test
	void countsTheProgramsStartupsAwaitingApproval() {
		joins(PROGRAM, organization("Bekleyen", UserAccountStatus.PENDING));
		joins(PROGRAM, organization("Onaylı", UserAccountStatus.APPROVED));
		joins(OTHER_PROGRAM, organization("Başka", UserAccountStatus.PENDING));
		entityManager.flush();

		long counted = organizations.countInProgramByApprovalStatus(PROGRAM, UserAccountStatus.PENDING);
		assertThat(counted).isEqualTo(1);
		assertThat(counted).isEqualTo(organizations.findByIdInAndApprovalStatusOrderByCreatedAtAsc(
				programStartups.findByProgramIdOrderByCreatedAtAsc(PROGRAM).stream()
						.map(ProgramStartup::getOrganizationId)
						.collect(Collectors.toSet()),
				UserAccountStatus.PENDING).size());
	}

	@Test
	void countsTeamChangesStillWaitingInTheProgram() {
		Organization team = organization("Takım", UserAccountStatus.APPROVED);
		AppUser member = user("member@test", UserRole.MENTEE, UserAccountStatus.APPROVED, PROGRAM);
		teamChange(team, member, PROGRAM, TeamChangeStatus.PENDING);
		teamChange(team, member, PROGRAM, TeamChangeStatus.APPROVED);
		teamChange(team, member, OTHER_PROGRAM, TeamChangeStatus.PENDING);
		entityManager.flush();

		long counted = teamChanges.countByProgramIdAndStatus(PROGRAM, TeamChangeStatus.PENDING);
		assertThat(counted).isEqualTo(1);
		assertThat(counted).isEqualTo(
				teamChanges.findByProgramIdAndStatusOrderByCreatedAtAsc(PROGRAM, TeamChangeStatus.PENDING).size());
	}

	private AppUser user(String email, UserRole role, UserAccountStatus status, UUID programId) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(email);
		user.setRole(role);
		user.setStatus(status);
		user.setProgramId(programId);
		entityManager.persist(user);
		return user;
	}

	private void membership(UUID programId, ProgramMentorStatus status, ProgramMentorInvitedBy invitedBy) {
		AppUser mentor = user(UUID.randomUUID() + "@test", UserRole.MENTOR, UserAccountStatus.APPROVED, null);
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(programId);
		membership.setMentorId(mentor.getId());
		membership.setStatus(status);
		membership.setInvitedBy(invitedBy);
		membership.setCreatedAt(NOW);
		entityManager.persist(membership);
	}

	private Organization organization(String name, UserAccountStatus approvalStatus) {
		Organization organization = new Organization();
		organization.setName(name);
		organization.setApprovalStatus(approvalStatus);
		entityManager.persist(organization);
		return organization;
	}

	private void joins(UUID programId, Organization organization) {
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(programId);
		startup.setOrganizationId(organization.getId());
		startup.setCreatedAt(NOW);
		entityManager.persist(startup);
	}

	private void teamChange(Organization team, AppUser member, UUID programId, TeamChangeStatus status) {
		TeamChangeRequest request = new TeamChangeRequest();
		request.setOrganization(team);
		request.setProgramId(programId);
		request.setMember(member);
		request.setRequestedBy(member);
		request.setKind(TeamChangeKind.LEAVE);
		request.setStatus(status);
		request.setCreatedAt(NOW);
		entityManager.persist(request);
	}
}
