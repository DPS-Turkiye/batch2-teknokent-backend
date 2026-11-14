package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.StartupSector;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AdminOverviewResponse;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class AdminOverviewServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private MentorshipService mentorshipService;
	@Mock
	private MentorshipRepository mentorshipRepository;
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
				.isInstanceOfSatisfying(ResponseStatusException.class,
						refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void anActiveAdminGrantWorksWhenAdminIsNotThePrimaryRole() {
		AppUser admin = person("Dual permission", UserRole.ADMIN);
		org.springframework.test.util.ReflectionTestUtils.setField(admin, "role", UserRole.MENTEE);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED)).thenReturn(List.of(admin));

		assertThat(service.overview(admin.getId()).users()).hasSize(1);
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
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED)).thenReturn(List.of(admin, elif, burcu));
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(byAdmin, byCoordinator, unassigned));
		when(programCoordinatorRepository.findByProgramIdOrderByCreatedAtAsc(byAdmin.getId()))
				.thenReturn(List.of(assignment(byAdmin, elif), assignment(byAdmin, burcu)));

		var programs = service.overview(admin.getId()).programs();

		assertThat(programs).extracting(row -> row.coordinatorName())
				.containsExactly("Elif Demir, Burcu Şen", "Elif Demir", null);
		assertThat(programs.get(0).coordinatorId()).isEqualTo(elif.getId());
		assertThat(programs.get(0).coordinatorIds()).containsExactly(elif.getId(), burcu.getId());
		assertThat(programs.get(1).coordinatorIds()).containsExactly(elif.getId());
		assertThat(programs.get(2).coordinatorIds()).isEmpty();
		assertThat(programs.get(2).coordinatorId()).isNull();
		assertThat(programs).extracting(row -> row.type()).containsOnly(ProgramType.STARTUP_MENTORING);
	}

	@Test
	void listsEveryActivePermissionOfAUserNotOnlyThePrimaryRole() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser academic = person("Deniz Hoca", UserRole.ACADEMICIAN);
		academic.grant(UserRole.MENTOR, PermissionSource.INSTITUTION, Instant.now());
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED)).thenReturn(List.of(admin, academic));

		var users = service.overview(admin.getId()).users();

		assertThat(users).filteredOn(row -> row.id().equals(academic.getId())).singleElement().satisfies(row -> {
			assertThat(row.role()).isEqualTo(UserRole.ACADEMICIAN);
			assertThat(row.roles()).containsExactlyInAnyOrder(UserRole.ACADEMICIAN, UserRole.MENTOR);
		});
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
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED))
				.thenReturn(List.of(admin, waiting, ready, mentor));
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
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED)).thenReturn(List.of(admin, mentor));
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
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId())).thenReturn(List.of(startup));
		when(organizationRepository.findAllById(Set.of(inProgram.getId()))).thenReturn(List.of(inProgram, elsewhere));
		when(meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(Set.of(inProgram.getId())))
				.thenReturn(List.of(meeting(mentor, inProgram), meeting(mentor, elsewhere)));

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
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId())).thenReturn(List.of(startup));
		when(mentorshipService.findByOrganizations(Set.of(vega.getId()))).thenReturn(List.of(
				mentorship(kemal, vega, stars), mentorship(deniz, vega, growth), mentorship(selin, vega, null)));

		var report = service.programReport(stars);

		assertThat(report.mentorships()).extracting(row -> row.mentorName() + ":" + row.programId())
				.containsExactly("Kemal Arslan:" + stars.getId(), "Selin Ak:null");
	}

	// An admin moved Vega from Stars to Growth: what Vega did in Stars stays in Stars' report, and only that.
	@Test
	void programReportKeepsWhatATeamThatLeftDidInTheProgramAndLeavesItOutOfTheNewOne() {
		AppUser elif = person("Elif Demir", UserRole.COORDINATOR);
		AppUser kemal = person("Kemal Arslan", UserRole.MENTOR);
		AppUser deniz = person("Deniz Kaya", UserRole.MENTOR);
		Program stars = program("METU Stars 2026", elif.getId());
		Program growth = program("Growth Circuit 2026", elif.getId());
		Organization vega = organization("Vega");
		Mentorship inStars = mentorshipEntity(kemal, vega, stars);
		Mentorship inGrowth = mentorshipEntity(deniz, vega, growth);
		ProgramStartup nowInGrowth = new ProgramStartup();
		nowInGrowth.setProgramId(growth.getId());
		nowInGrowth.setOrganizationId(vega.getId());
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId())).thenReturn(List.of());
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(growth.getId()))
				.thenReturn(List.of(nowInGrowth));
		when(mentorshipRepository.findTeamsWithMentorshipIn(List.of(stars.getId())))
				.thenReturn(List.of(programTeam(stars, vega)));
		when(mentorshipRepository.findTeamsWithMentorshipIn(List.of(growth.getId())))
				.thenReturn(List.of(programTeam(growth, vega)));
		when(mentorshipRepository.findByOrganizationIdIn(Set.of(vega.getId()))).thenReturn(List.of(inStars, inGrowth));
		when(mentorshipService.findByOrganizations(Set.of(vega.getId()))).thenReturn(List.of(
				MentorshipResponse.fromEntity(inStars, List.of()), MentorshipResponse.fromEntity(inGrowth, List.of()),
				mentorship(deniz, vega, null)));
		// Follow-up meetings carry no program of their own; the mentor's mentorship says where they were held.
		when(meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(Set.of(vega.getId())))
				.thenReturn(List.of(meeting(kemal, vega), meeting(deniz, vega)));

		var oldReport = service.programReport(stars);
		var newReport = service.programReport(growth);

		assertThat(oldReport.mentorships()).extracting(row -> row.mentorName()).containsExactly("Kemal Arslan");
		assertThat(oldReport.meetings()).extracting(mark -> mark.mentorId()).containsExactly(kemal.getId());
		assertThat(oldReport.meetingsLast8Weeks()).isEqualTo(1);
		assertThat(newReport.mentorships()).extracting(row -> row.mentorName() + ":" + row.programId())
				.containsExactly("Deniz Kaya:" + growth.getId(), "Deniz Kaya:null");
		assertThat(newReport.meetings()).extracting(mark -> mark.mentorId()).containsExactly(deniz.getId());
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
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED)).thenReturn(List.of(admin, kemal, deniz));
		when(mentorshipService.findAll()).thenReturn(List.of(
				mentorship(kemal, vega, stars), mentorship(deniz, vega, growth)));

		var mentorships = service.overview(admin.getId()).mentorships();

		assertThat(mentorships).extracting(row -> row.mentorName() + ":" + row.programId())
				.containsExactly("Kemal Arslan:" + stars.getId(), "Deniz Kaya:" + growth.getId());
	}

	@Test
	void listsOnlyBottlenecksThatAreStillOpenOrInProgress() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		Organization vega = organization("Vega");
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED)).thenReturn(List.of(admin));
		when(bottleneckRepository.findAll()).thenReturn(List.of(
				bottleneck(vega, "Hukuk", BottleneckStatus.OPEN, BottleneckSeverity.HIGH),
				bottleneck(vega, "Satış", BottleneckStatus.IN_PROGRESS, BottleneckSeverity.LOW),
				bottleneck(vega, "Ekip", BottleneckStatus.RESOLVED, BottleneckSeverity.CRITICAL),
				bottleneck(vega, "Finansman", BottleneckStatus.CANCELLED, BottleneckSeverity.MEDIUM)));

		var bottlenecks = service.overview(admin.getId()).bottlenecks();

		assertThat(bottlenecks)
				.extracting(AdminOverviewResponse.BottleneckRow::title, AdminOverviewResponse.BottleneckRow::severity,
						AdminOverviewResponse.BottleneckRow::status,
						AdminOverviewResponse.BottleneckRow::organizationName)
				.containsExactly(tuple("Hukuk", BottleneckSeverity.HIGH, BottleneckStatus.OPEN, "Vega"),
						tuple("Satış", BottleneckSeverity.LOW, BottleneckStatus.IN_PROGRESS, "Vega"));
		assertThat(bottlenecks.getFirst().organizationId()).isEqualTo(vega.getId());
	}

	@Test
	void aProgramRowListsItsStartupsWithTheirFounderAndItsMentorsWithTheirStatus() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser elif = person("Elif Demir", UserRole.COORDINATOR);
		AppUser kemal = person("Kemal Arslan", UserRole.MENTOR);
		AppUser deniz = person("Deniz Kaya", UserRole.MENTOR);
		AppUser gamze = person("Gamze Kozak", UserRole.MENTEE);
		gamze.setJobTitle("CEO");
		Program stars = program("METU Stars 2026", elif.getId());
		Organization vega = organization("Vega");
		vega.setFounder(gamze);
		vega.setSector(StartupSector.FINTECH);
		Organization withoutFounder = organization("Nova");
		UUID goneOrganizationId = UUID.randomUUID();
		UUID goneMentorId = UUID.randomUUID();
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED))
				.thenReturn(List.of(admin, elif, kemal, deniz, gamze));
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(stars));
		when(organizationRepository.findAll()).thenReturn(List.of(vega, withoutFounder));
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId())).thenReturn(List.of(
				startup(stars, vega.getId()), startup(stars, withoutFounder.getId()),
				startup(stars, goneOrganizationId)));
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId())).thenReturn(List.of(
				membership(kemal.getId(), ProgramMentorStatus.ACCEPTED),
				membership(goneMentorId, ProgramMentorStatus.PENDING),
				membership(deniz.getId(), ProgramMentorStatus.REJECTED)));

		var row = service.overview(admin.getId()).programs().getFirst();

		assertThat(row.status()).isEqualTo(ProgramStatus.ACTIVE);
		assertThat(row.startupCount()).isEqualTo(3);
		assertThat(row.acceptedMentorCount()).isEqualTo(1);
		assertThat(row.pendingMentorCount()).isEqualTo(1);
		assertThat(row.startups()).containsExactly(
				new AdminOverviewResponse.StartupRow(vega.getId(), "Vega", "FinTech", stars.getId(), "METU Stars 2026",
						"Gamze Kozak", "CEO"),
				new AdminOverviewResponse.StartupRow(withoutFounder.getId(), "Nova", null, stars.getId(),
						"METU Stars 2026", null, null),
				new AdminOverviewResponse.StartupRow(goneOrganizationId, "Takım", null, stars.getId(),
						"METU Stars 2026", null, null));
		assertThat(row.mentors()).containsExactly(
				new AdminOverviewResponse.MentorMembershipRow(kemal.getId(), "Kemal Arslan", kemal.getEmail(),
						ProgramMentorStatus.ACCEPTED),
				new AdminOverviewResponse.MentorMembershipRow(goneMentorId, "Mentor", "", ProgramMentorStatus.PENDING),
				new AdminOverviewResponse.MentorMembershipRow(deniz.getId(), "Deniz Kaya", deniz.getEmail(),
						ProgramMentorStatus.REJECTED));
	}

	@Test
	void countsTheMeetingsHeldInTheLastEightWeeksAndMarksEveryMeetingThatHasAMentor() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser kemal = person("Kemal Arslan", UserRole.MENTOR);
		Organization vega = organization("Vega");
		Meeting upcoming = meeting(kemal, vega, Duration.ofDays(-2), MeetingStatus.SCHEDULED);
		Meeting recent = meeting(kemal, vega, Duration.ofDays(3), MeetingStatus.COMPLETED);
		Meeting withoutMentor = meeting(null, vega, Duration.ofDays(4), MeetingStatus.COMPLETED);
		Meeting cancelled = meeting(kemal, vega, Duration.ofDays(5), MeetingStatus.CANCELLED);
		Meeting tooOld = meeting(kemal, vega, Duration.ofDays(60), MeetingStatus.COMPLETED);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED)).thenReturn(List.of(admin, kemal));
		when(meetingRepository.findAllByOrderByScheduledAtDesc())
				.thenReturn(List.of(upcoming, recent, withoutMentor, cancelled, tooOld));

		var overview = service.overview(admin.getId());

		assertThat(overview.meetingsLast8Weeks()).isEqualTo(2);
		assertThat(overview.meetings())
				.extracting(AdminOverviewResponse.MeetingMark::scheduledAt, AdminOverviewResponse.MeetingMark::status)
				.containsExactly(
						tuple(upcoming.getScheduledAt(), MeetingStatus.SCHEDULED),
						tuple(recent.getScheduledAt(), MeetingStatus.COMPLETED),
						tuple(cancelled.getScheduledAt(), MeetingStatus.CANCELLED),
						tuple(tooOld.getScheduledAt(), MeetingStatus.COMPLETED));
	}

	@Test
	void aMentorshipRowSaysHowLongAgoThePairLastMetIgnoringCancelledAndUpcomingMeetings() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser kemal = person("Kemal Arslan", UserRole.MENTOR);
		AppUser deniz = person("Deniz Kaya", UserRole.MENTOR);
		Organization vega = organization("Vega");
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED)).thenReturn(List.of(admin, kemal, deniz));
		when(meetingRepository.findAllByOrderByScheduledAtDesc()).thenReturn(List.of(
				meeting(kemal, vega, Duration.ofDays(-2), MeetingStatus.SCHEDULED),
				meeting(kemal, vega, Duration.ofDays(1), MeetingStatus.CANCELLED),
				meeting(kemal, vega, Duration.ofDays(3), MeetingStatus.COMPLETED),
				meeting(kemal, vega, Duration.ofDays(10), MeetingStatus.COMPLETED)));
		when(mentorshipService.findAll()).thenReturn(List.of(
				mentorship(kemal, vega, null), mentorship(deniz, vega, null)));

		var mentorships = service.overview(admin.getId()).mentorships();

		assertThat(mentorships)
				.extracting(AdminOverviewResponse.MentorshipRow::mentorName,
						AdminOverviewResponse.MentorshipRow::daysSinceMeeting)
				.containsExactly(tuple("Kemal Arslan", 3L), tuple("Deniz Kaya", null));
	}

	@Test
	void aUserRowNamesTheirStartupButNeverAPersonalTeam() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser founder = person("Gamze Kozak", UserRole.MENTEE);
		Organization vega = organization("Vega");
		founder.setOrganization(vega);
		founder.setJobTitle("CEO");
		founder.setExpertise(List.of("Satış"));
		AppUser student = person("ayşe Öğrenci", UserRole.MENTEE);
		Organization personal = organization("ayşe Öğrenci");
		personal.setPersonal(true);
		student.setOrganization(personal);
		student.setStatus(UserAccountStatus.PENDING);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED))
				.thenReturn(List.of(founder, admin, student));

		var users = service.overview(admin.getId()).users();

		assertThat(users)
				.extracting(AdminOverviewResponse.UserRow::fullName, AdminOverviewResponse.UserRow::status,
						AdminOverviewResponse.UserRow::organizationId, AdminOverviewResponse.UserRow::organizationName)
				.containsExactly(
						tuple("ayşe Öğrenci", UserAccountStatus.PENDING, personal.getId(), null),
						tuple("Gamze Kozak", UserAccountStatus.APPROVED, vega.getId(), "Vega"),
						tuple("Platform Yöneticisi", UserAccountStatus.APPROVED, null, null));
		assertThat(users.get(1).jobTitle()).isEqualTo("CEO");
		assertThat(users.get(1).expertise()).containsExactly("Satış");
	}

	@Test
	void onlyAnApprovedCoordinatorWithALoginIsAskedAboutTheirPassword() {
		AppUser admin = person("Platform Yöneticisi", UserRole.ADMIN);
		AppUser awaitingApproval = person("Ayla Bekler", UserRole.COORDINATOR);
		awaitingApproval.setStatus(UserAccountStatus.PENDING);
		awaitingApproval.setFirebaseUid("uid-ayla");
		AppUser withoutLogin = person("Burcu Şen", UserRole.COORDINATOR);
		withoutLogin.setStatus(UserAccountStatus.APPROVED);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(appUserRepository.findByStatusNot(UserAccountStatus.DELETED))
				.thenReturn(List.of(admin, awaitingApproval, withoutLogin));

		var users = service.overview(admin.getId()).users();

		verify(loginProvisioner).passwordsSet(List.of());
		assertThat(users).extracting(AdminOverviewResponse.UserRow::passwordSet).containsOnlyNulls();
	}

	@Test
	void programReportListsOnlyTheBottlenecksOfTheProgramsTeams() {
		AppUser elif = person("Elif Demir", UserRole.COORDINATOR);
		Program stars = program("METU Stars 2026", elif.getId());
		Organization vega = organization("Vega");
		Organization elsewhere = organization("Başka takım");
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId()))
				.thenReturn(List.of(startup(stars, vega.getId())));
		when(bottleneckRepository.findByOrganizationIdInAndStatusIn(Set.of(vega.getId()),
				List.of(BottleneckStatus.OPEN, BottleneckStatus.IN_PROGRESS))).thenReturn(List.of(
						bottleneck(elsewhere, "Hukuk", BottleneckStatus.OPEN, BottleneckSeverity.HIGH),
						bottleneck(vega, "Satış", BottleneckStatus.OPEN, BottleneckSeverity.LOW)));

		assertThat(service.programReport(stars).bottlenecks())
				.extracting(AdminOverviewResponse.BottleneckRow::title)
				.containsExactly("Satış");
	}

	@Test
	void aProgramWithoutTeamsReportsNothingAndLoadsNothingOfOtherTeams() {
		AppUser elif = person("Elif Demir", UserRole.COORDINATOR);
		Program stars = program("METU Stars 2026", elif.getId());

		var report = service.programReport(stars);

		assertThat(report.programs()).singleElement()
				.satisfies(row -> assertThat(row.startupCount()).isZero());
		assertThat(report.mentorships()).isEmpty();
		assertThat(report.meetings()).isEmpty();
		assertThat(report.meetingsLast8Weeks()).isZero();
		assertThat(report.bottlenecks()).isEmpty();
		verify(mentorshipRepository, never()).findByOrganizationIdIn(any());
		verify(meetingRepository, never()).findByOrganizationIdInOrderByScheduledAtDesc(any());
		verify(mentorshipService, never()).findByOrganizations(any());
		verify(bottleneckRepository, never()).findByOrganizationIdInAndStatusIn(any(), any());
		verify(organizationRepository, never()).findAllById(any());
	}

	// A program's report reads its own teams' rows and the people its row names, never the whole platform's.
	@Test
	void programReportLoadsOnlyItsOwnTeamsRowsAndThePeopleItNames() {
		AppUser elif = person("Elif Demir", UserRole.COORDINATOR);
		AppUser kemal = person("Kemal Arslan", UserRole.MENTOR);
		AppUser gone = person("Silinmiş Mentor", UserRole.MENTOR);
		gone.setStatus(UserAccountStatus.DELETED);
		Program stars = program("METU Stars 2026", elif.getId());
		Organization vega = organization("Vega");
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId()))
				.thenReturn(List.of(startup(stars, vega.getId())));
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(stars.getId())).thenReturn(List.of(
				membership(kemal.getId(), ProgramMentorStatus.ACCEPTED),
				membership(gone.getId(), ProgramMentorStatus.ACCEPTED)));
		when(appUserRepository.findAllById(Set.of(elif.getId(), kemal.getId(), gone.getId())))
				.thenReturn(List.of(elif, kemal, gone));
		when(organizationRepository.findAllById(Set.of(vega.getId()))).thenReturn(List.of(vega));
		when(meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(Set.of(vega.getId())))
				.thenReturn(List.of(meeting(kemal, vega)));
		when(mentorshipService.findByOrganizations(Set.of(vega.getId())))
				.thenReturn(List.of(mentorship(kemal, vega, stars)));
		when(bottleneckRepository.findByOrganizationIdInAndStatusIn(Set.of(vega.getId()),
				List.of(BottleneckStatus.OPEN, BottleneckStatus.IN_PROGRESS)))
				.thenReturn(List.of(bottleneck(vega, "Satış", BottleneckStatus.OPEN, BottleneckSeverity.LOW)));

		var report = service.programReport(stars);

		var row = report.programs().getFirst();
		assertThat(row.coordinatorName()).isEqualTo("Elif Demir");
		assertThat(row.startups()).extracting(AdminOverviewResponse.StartupRow::name).containsExactly("Vega");
		assertThat(row.mentors()).extracting(AdminOverviewResponse.MentorMembershipRow::fullName)
				.containsExactly("Kemal Arslan", "Mentor");
		assertThat(report.mentorships()).extracting(AdminOverviewResponse.MentorshipRow::daysSinceMeeting)
				.containsExactly(3L);
		assertThat(report.meetings()).extracting(AdminOverviewResponse.MeetingMark::organizationId)
				.containsExactly(vega.getId());
		assertThat(report.bottlenecks()).extracting(AdminOverviewResponse.BottleneckRow::title)
				.containsExactly("Satış");
		verify(appUserRepository, never()).findByStatusNot(any());
		verify(organizationRepository, never()).findAll();
		verify(meetingRepository, never()).findAllByOrderByScheduledAtDesc();
		verify(mentorshipService, never()).findAll();
		verify(bottleneckRepository, never()).findAll();
	}

	private static Bottleneck bottleneck(Organization organization, String title, BottleneckStatus status,
			BottleneckSeverity severity) {
		Bottleneck bottleneck = new Bottleneck();
		bottleneck.setId(UUID.randomUUID());
		bottleneck.setOrganization(organization);
		bottleneck.setTitle(title);
		bottleneck.setStatus(status);
		bottleneck.setSeverity(severity);
		return bottleneck;
	}

	private static ProgramStartup startup(Program program, UUID organizationId) {
		ProgramStartup startup = new ProgramStartup();
		startup.setProgramId(program.getId());
		startup.setOrganizationId(organizationId);
		return startup;
	}

	private static ProgramMentor membership(UUID mentorId, ProgramMentorStatus status) {
		ProgramMentor membership = new ProgramMentor();
		membership.setMentorId(mentorId);
		membership.setStatus(status);
		return membership;
	}

	private static Meeting meeting(AppUser mentor, Organization organization, Duration ago, MeetingStatus status) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentor(mentor);
		meeting.setOrganization(organization);
		meeting.setScheduledAt(Instant.now().minus(ago));
		meeting.setStatus(status);
		return meeting;
	}

	private static MentorshipResponse mentorship(AppUser mentor, Organization organization, Program program) {
		return MentorshipResponse.fromEntity(mentorshipEntity(mentor, organization, program), List.of());
	}

	private static MentorshipRepository.ProgramTeam programTeam(Program program, Organization organization) {
		return new MentorshipRepository.ProgramTeam() {
			@Override
			public UUID getProgramId() {
				return program.getId();
			}

			@Override
			public UUID getOrganizationId() {
				return organization.getId();
			}
		};
	}

	private static Mentorship mentorshipEntity(AppUser mentor, Organization organization, Program program) {
		Mentorship mentorship = new Mentorship();
		mentorship.setId(UUID.randomUUID());
		mentorship.setMentor(mentor);
		mentorship.setOrganization(organization);
		mentorship.setProgram(program);
		mentorship.setStatus(MentorshipStatus.ACTIVE);
		mentorship.setStartDate(java.time.LocalDate.now(MeetingSlotTimes.APP_ZONE).minusDays(3));
		return mentorship;
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
