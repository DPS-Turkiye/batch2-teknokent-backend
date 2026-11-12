package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.NewCoordinatorInput;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse.CoordinatorRef;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.service.AdminCoordinatorService.CheckedInvite;

@ExtendWith(MockitoExtension.class)
class ProgramCoordinatorServiceTest {

	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private NotificationService notificationService;
	@Mock
	private AdminCoordinatorService adminCoordinatorService;
	@InjectMocks
	private ProgramCoordinatorService service;

	private static final UUID ELIF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID BURCU = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
	private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	private final Program program = program();

	@Test
	void anApprovedCoordinatorIsAddedAndTold() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, "Admin", UserRole.ADMIN));
		when(programService.requireManagedProgram(ADMIN, PROGRAM_ID)).thenReturn(program);
		when(appUserRepository.findById(BURCU)).thenReturn(Optional.of(user(BURCU, "Burcu Şen", UserRole.COORDINATOR)));
		when(programService.coordinatorsOf(program)).thenReturn(List.of(
				new CoordinatorRef(ELIF, "Elif Demir", true), new CoordinatorRef(BURCU, "Burcu Şen", false)));

		var result = service.add(ADMIN, PROGRAM_ID, BURCU);

		ArgumentCaptor<ProgramCoordinator> saved = ArgumentCaptor.forClass(ProgramCoordinator.class);
		verify(programCoordinatorRepository).save(saved.capture());
		assertThat(saved.getValue().getProgramId()).isEqualTo(PROGRAM_ID);
		assertThat(saved.getValue().getUserId()).isEqualTo(BURCU);
		assertThat(saved.getValue().getAddedBy()).isEqualTo(ADMIN);
		assertThat(result).extracting(CoordinatorRef::fullName).containsExactly("Elif Demir", "Burcu Şen");
		verify(notificationService).create(any(), anyString(), anyString(), eq("/coordinator/programs/" + PROGRAM_ID));
	}

	@Test
	void anInvitedEmailIsAddedThroughTheSameInviteAsANewProgram() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, "Admin", UserRole.ADMIN));
		when(programService.requireManagedProgram(ADMIN, PROGRAM_ID)).thenReturn(program);
		UUID invited = UUID.randomUUID();
		CheckedInvite checked = new CheckedInvite("yeni@x.org", "Yeni Kişi", null);
		when(adminCoordinatorService.checkNewProgramCoordinator(ADMIN, "yeni@x.org", "Yeni Kişi")).thenReturn(checked);
		when(adminCoordinatorService.coordinatorForNewProgram(ADMIN, checked, program.getName()))
				.thenReturn(user(invited, "Yeni Kişi", UserRole.COORDINATOR));

		service.invite(ADMIN, PROGRAM_ID, new NewCoordinatorInput("Yeni Kişi", "yeni@x.org"));

		ArgumentCaptor<ProgramCoordinator> saved = ArgumentCaptor.forClass(ProgramCoordinator.class);
		verify(programCoordinatorRepository).save(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo(invited);
		assertThat(saved.getValue().getProgramId()).isEqualTo(PROGRAM_ID);
	}

	@Test
	void onlyAnAdminInvitesAndAnEmailAlreadyRunningTheProgramIsRefused() {
		when(programService.requireCoordinatorOrAdmin(ELIF)).thenReturn(user(ELIF, "Elif", UserRole.COORDINATOR));
		assertThatThrownBy(() -> service.invite(ELIF, PROGRAM_ID, new NewCoordinatorInput("Yeni", "yeni@x.org")))
				.isInstanceOf(InvalidRequestException.class);
		verify(adminCoordinatorService, never()).checkNewProgramCoordinator(any(), any(), any());

		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, "Admin", UserRole.ADMIN));
		when(programService.requireManagedProgram(ADMIN, PROGRAM_ID)).thenReturn(program);
		when(adminCoordinatorService.checkNewProgramCoordinator(ADMIN, "burcu@x.org", "Burcu"))
				.thenReturn(new CheckedInvite("burcu@x.org", "Burcu Şen", user(BURCU, "Burcu Şen", UserRole.COORDINATOR)));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(PROGRAM_ID, BURCU)).thenReturn(true);
		assertThatThrownBy(() -> service.invite(ADMIN, PROGRAM_ID, new NewCoordinatorInput("Burcu", "burcu@x.org")))
				.isInstanceOf(InvalidRequestException.class);
		verify(adminCoordinatorService, never()).coordinatorForNewProgram(any(), any(), any());
		verify(programCoordinatorRepository, never()).save(any());
	}

	@Test
	void mentorsAndCoordinatorsAlreadyOnTheProgramAreRefused() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, "Admin", UserRole.ADMIN));
		when(programService.requireManagedProgram(ADMIN, PROGRAM_ID)).thenReturn(program);
		UUID mentorId = UUID.randomUUID();
		when(appUserRepository.findById(mentorId)).thenReturn(Optional.of(user(mentorId, "Ayşe", UserRole.MENTOR)));
		when(appUserRepository.findById(BURCU)).thenReturn(Optional.of(user(BURCU, "Burcu Şen", UserRole.COORDINATOR)));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(PROGRAM_ID, BURCU)).thenReturn(true);

		assertThatThrownBy(() -> service.add(ADMIN, PROGRAM_ID, mentorId)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.add(ADMIN, PROGRAM_ID, BURCU)).isInstanceOf(InvalidRequestException.class);
		verify(programCoordinatorRepository, never()).save(any());
	}

	@Test
	void theCreatorCannotBeRemovedButAnAddedCoordinatorCan() {
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, "Admin", UserRole.ADMIN));
		when(programService.requireManagedProgram(ADMIN, PROGRAM_ID)).thenReturn(program);
		ProgramCoordinator row = new ProgramCoordinator();
		row.setUserId(BURCU);
		when(programCoordinatorRepository.findByProgramIdAndUserId(PROGRAM_ID, BURCU)).thenReturn(Optional.of(row));
		when(programService.coordinatorsOf(program)).thenReturn(List.of(
				new CoordinatorRef(ELIF, "Elif Demir", true), new CoordinatorRef(BURCU, "Burcu Şen", false)));

		assertThatThrownBy(() -> service.remove(ADMIN, PROGRAM_ID, ELIF)).isInstanceOf(InvalidRequestException.class);
		service.remove(ADMIN, PROGRAM_ID, BURCU);

		verify(programCoordinatorRepository).delete(row);
	}

	@Test
	void onlyTheAdminChangesAProgramsCoordinators() {
		when(programService.requireCoordinatorOrAdmin(ELIF)).thenReturn(user(ELIF, "Elif Demir", UserRole.COORDINATOR));

		assertThatThrownBy(() -> service.add(ELIF, PROGRAM_ID, BURCU))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("admin");
		assertThatThrownBy(() -> service.remove(ELIF, PROGRAM_ID, BURCU)).isInstanceOf(InvalidRequestException.class);
		verify(programCoordinatorRepository, never()).save(any());
		verify(programCoordinatorRepository, never()).delete(any());
	}

	@Test
	void theLastCoordinatorOfAnAdminsProgramStays() {
		Program byAdmin = program();
		byAdmin.setCreatedBy(ADMIN);
		when(programService.requireCoordinatorOrAdmin(ADMIN)).thenReturn(user(ADMIN, "Admin", UserRole.ADMIN));
		when(programService.requireManagedProgram(ADMIN, PROGRAM_ID)).thenReturn(byAdmin);
		ProgramCoordinator row = new ProgramCoordinator();
		row.setUserId(BURCU);
		when(programCoordinatorRepository.findByProgramIdAndUserId(PROGRAM_ID, BURCU)).thenReturn(Optional.of(row));
		when(programService.coordinatorsOf(byAdmin)).thenReturn(List.of(new CoordinatorRef(BURCU, "Burcu Şen", false)));

		assertThatThrownBy(() -> service.remove(ADMIN, PROGRAM_ID, BURCU))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("at least one");
		verify(programCoordinatorRepository, never()).delete(any());
	}

	@Test
	void candidatesLeaveOutWhoeverAlreadyRunsTheProgram() {
		when(programService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program);
		UUID can = UUID.randomUUID();
		when(programCoordinatorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of());
		when(appUserRepository.findByPermissionAndStatusOrderByFullNameAsc(UserRole.COORDINATOR, UserAccountStatus.APPROVED))
				.thenReturn(List.of(user(ELIF, "Elif Demir", UserRole.COORDINATOR), user(can, "Can Yılmaz", UserRole.COORDINATOR)));

		assertThat(service.candidates(ELIF, PROGRAM_ID)).extracting(CoordinatorRef::id).containsExactly(can);
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setCreatedBy(ELIF);
		program.setCreatedAt(Instant.parse("2026-02-01T09:00:00Z"));
		return program;
	}

	private static AppUser user(UUID id, String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName(name);
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}
}
