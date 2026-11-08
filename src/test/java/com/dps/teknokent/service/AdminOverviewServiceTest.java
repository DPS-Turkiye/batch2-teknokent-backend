package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.LoginProvisioner;

@ExtendWith(MockitoExtension.class)
class AdminOverviewServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private MentorshipService mentorshipService;
	@Mock
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private BottleneckRepository bottleneckRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private LoginProvisioner loginProvisioner;

	@InjectMocks
	private AdminOverviewService service;

	@Test
	void rejectsAnyoneWhoIsNotAnAdmin() {
		AppUser mentor = new AppUser();
		mentor.setRole(UserRole.MENTOR);
		UUID actorId = UUID.randomUUID();
		when(domainLookupService.requireUser(actorId)).thenReturn(mentor);

		assertThatThrownBy(() -> service.overview(actorId))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Only an admin can view the admin overview.");
	}

	@Test
	void listsAProgramsAssignedCoordinatorsAndNotTheAdminWhoCreatedIt() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser elif = person("Elif Demir", UserRole.COORDINATOR);
		AppUser burcu = person("Burcu Şen", UserRole.COORDINATOR);
		Program byAdmin = program("Admin programı", admin.getId());
		Program byCoordinator = program("METU Stars 2026", elif.getId());
		Program unassigned = program("Boş program", admin.getId());
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findAll()).thenReturn(List.of(admin, elif, burcu));
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(byAdmin, byCoordinator, unassigned));
		when(programCoordinatorRepository.findByProgramIdOrderByCreatedAtAsc(byAdmin.getId()))
				.thenReturn(List.of(assignment(byAdmin, elif), assignment(byAdmin, burcu)));

		var programs = service.overview(admin.getId()).programs();

		assertThat(programs).extracting(row -> row.coordinatorName())
				.containsExactly("Elif Demir, Burcu Şen", "Elif Demir", null);
		assertThat(programs.get(0).coordinatorId()).isEqualTo(elif.getId());
		assertThat(programs.get(2).coordinatorId()).isNull();
		assertThat(programs).extracting(row -> row.type()).containsOnly("STARTUP_MENTORING");
	}

	@Test
	void saysWhetherAnAddedCoordinatorHasSetAPassword() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser waiting = person("Ayla Bekler", UserRole.COORDINATOR);
		AppUser ready = person("Burcu Şen", UserRole.COORDINATOR);
		AppUser mentor = person("Cem Mentor", UserRole.MENTOR);
		for (AppUser user : List.of(waiting, ready, mentor)) {
			user.setStatus(UserAccountStatus.APPROVED);
			user.setFirebaseUid("uid-" + user.getFullName());
		}
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findAll()).thenReturn(List.of(admin, waiting, ready, mentor));
		when(loginProvisioner.passwordsSet(List.of(waiting.getFirebaseUid(), ready.getFirebaseUid())))
				.thenReturn(Map.of(waiting.getFirebaseUid(), false, ready.getFirebaseUid(), true));

		var users = service.overview(admin.getId()).users();

		assertThat(users).extracting(row -> row.fullName() + ":" + row.passwordSet())
				.containsExactly("Ayla Bekler:false", "Burcu Şen:true", "Cem Mentor:null", "Platform Yöneticisi:null");
	}

	@Test
	void listsTheOpenRequestsStraightFromAStatusQuery() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser mentor = person("Cem Mentor", UserRole.MENTOR);
		MentorshipRequest open = new MentorshipRequest();
		open.setId(UUID.randomUUID());
		open.setOrganization(organization("Lumen"));
		open.setMentor(mentor);
		open.setSupportArea("Finans");
		open.setStatus(MentorshipRequestStatus.MORE_INFO_REQUESTED);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findAll()).thenReturn(List.of(admin, mentor));
		when(mentorshipRequestRepository.findByStatusInOrderByCreatedAtDesc(List.of(
				MentorshipRequestStatus.PENDING,
				MentorshipRequestStatus.MORE_INFO_REQUESTED,
				MentorshipRequestStatus.COUNTER_PROPOSED)))
				.thenReturn(List.of(open));

		var requests = service.overview(admin.getId()).requests();

		assertThat(requests).extracting(row -> row.organizationName() + " · " + row.mentorName() + " · "
				+ row.supportArea() + " · " + row.status())
				.containsExactly("Lumen · Cem Mentor · Finans · MORE_INFO_REQUESTED");
	}

	@Test
	void programReportKeepsOnlyThatProgramsTeamsAndLeavesOutUsersAndRequests() {
		AppUser elif = person("Elif Demir", UserRole.COORDINATOR);
		AppUser mentor = person("Kemal Arslan", UserRole.MENTOR);
		Program stars = program("METU Stars 2026", elif.getId());
		Organization inProgram = organization("Vega");
		Organization elsewhere = organization("Başka takım");
		ProgramStartup startup = new ProgramStartup();
		startup.setProgramId(stars.getId());
		startup.setOrganizationId(inProgram.getId());
		when(appUserRepository.findAll()).thenReturn(List.of(elif, mentor));
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId())).thenReturn(List.of(startup));
		when(organizationRepository.findAll()).thenReturn(List.of(inProgram, elsewhere));
		when(meetingRepository.findAllByOrderByScheduledAtDesc()).thenReturn(List.of(
				meeting(mentor, inProgram), meeting(mentor, elsewhere)));

		var report = service.programReport(stars);

		assertThat(report.programs()).extracting(row -> row.name()).containsExactly("METU Stars 2026");
		assertThat(report.meetings()).extracting(mark -> mark.organizationId()).containsExactly(inProgram.getId());
		assertThat(report.meetingsLast8Weeks()).isEqualTo(1);
		assertThat(report.users()).isEmpty();
		assertThat(report.requests()).isEmpty();
	}

	@Test
	void programReportCountsATeamsMentorshipOnlyInTheProgramItWasMadeIn() {
		AppUser elif = person("Elif Demir", UserRole.COORDINATOR);
		AppUser kemal = person("Kemal Arslan", UserRole.MENTOR);
		AppUser deniz = person("Deniz Kaya", UserRole.MENTOR);
		AppUser selin = person("Selin Ak", UserRole.MENTOR);
		Program stars = program("METU Stars 2026", elif.getId());
		Program growth = program("Growth Circuit 2026", elif.getId());
		Organization vega = organization("Vega");
		ProgramStartup startup = new ProgramStartup();
		startup.setProgramId(stars.getId());
		startup.setOrganizationId(vega.getId());
		when(appUserRepository.findAll()).thenReturn(List.of(elif, kemal, deniz, selin));
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId())).thenReturn(List.of(startup));
		when(mentorshipService.findAll()).thenReturn(List.of(
				mentorship(kemal, vega, stars), mentorship(deniz, vega, growth), mentorship(selin, vega, null)));

		var report = service.programReport(stars);

		assertThat(report.mentorships()).extracting(row -> row.mentorName() + ":" + row.programId())
				.containsExactly("Kemal Arslan:" + stars.getId(), "Selin Ak:null");
	}

	@Test
	void overviewTellsWhichProgramEachMentorshipWasMadeIn() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser kemal = person("Kemal Arslan", UserRole.MENTOR);
		AppUser deniz = person("Deniz Kaya", UserRole.MENTOR);
		Program stars = program("METU Stars 2026", admin.getId());
		Program growth = program("Growth Circuit 2026", admin.getId());
		Organization vega = organization("Vega");
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findAll()).thenReturn(List.of(admin, kemal, deniz));
		when(mentorshipService.findAll()).thenReturn(List.of(
				mentorship(kemal, vega, stars), mentorship(deniz, vega, growth)));

		var mentorships = service.overview(admin.getId()).mentorships();

		assertThat(mentorships).extracting(row -> row.mentorName() + ":" + row.programId())
				.containsExactly("Kemal Arslan:" + stars.getId(), "Deniz Kaya:" + growth.getId());
	}

	private static MentorshipResponse mentorship(AppUser mentor, Organization organization, Program program) {
		Mentorship mentorship = new Mentorship();
		mentorship.setId(UUID.randomUUID());
		mentorship.setMentor(mentor);
		mentorship.setOrganization(organization);
		mentorship.setProgram(program);
		mentorship.setStatus(MentorshipStatus.ACTIVE);
		mentorship.setStartDate(java.time.LocalDate.now().minusDays(3));
		return MentorshipResponse.fromEntity(mentorship);
	}

	private static Organization organization(String name) {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName(name);
		return organization;
	}

	private static Meeting meeting(AppUser mentor, Organization organization) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentor(mentor);
		meeting.setOrganization(organization);
		meeting.setScheduledAt(java.time.Instant.now().minus(java.time.Duration.ofDays(3)));
		meeting.setStatus(MeetingStatus.COMPLETED);
		return meeting;
	}

	private static AppUser person(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setEmail(user.getId() + "@tekno.test");
		user.setRole(role);
		return user;
	}

	private static Program program(String name, UUID createdBy) {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName(name);
		program.setCreatedBy(createdBy);
		program.setCreatedAt(java.time.Instant.parse("2026-09-01T09:00:00Z"));
		return program;
	}

	private static ProgramCoordinator assignment(Program program, AppUser coordinator) {
		ProgramCoordinator row = new ProgramCoordinator();
		row.setProgramId(program.getId());
		row.setUserId(coordinator.getId());
		return row;
	}
}
