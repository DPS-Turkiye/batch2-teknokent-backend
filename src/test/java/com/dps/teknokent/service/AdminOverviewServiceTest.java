package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class AdminOverviewServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private MentorshipService mentorshipService;
	@Mock
	private MentorshipRequestService mentorshipRequestService;
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
