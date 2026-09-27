package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.PostProgramAnnouncementRequest;
import com.dps.teknokent.dto.responses.ProgramAnnouncementResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramAnnouncement;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramAnnouncementRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class ProgramAnnouncementServiceTest {

	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private ProgramAnnouncementRepository announcementRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private ProgramAnnouncementService service;

	private final AppUser coordinator = user("Elif Demir", UserRole.COORDINATOR, null);
	private final Organization lumen = organization();
	private final AppUser deniz = user("Deniz Ak", UserRole.MENTEE, lumen);
	private final AppUser ayse = user("Ayşe Demir", UserRole.MENTOR, null);
	private final AppUser pendingMentor = user("Kerem Sarı", UserRole.MENTOR, null);
	private final Program program = program();

	@BeforeEach
	void setUp() {
		lenient().when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId()))
				.thenReturn(List.of(startup(lumen.getId())));
		lenient().when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(lumen.getId())).thenReturn(List.of(deniz));
		lenient().when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(program.getId())).thenReturn(List.of(
				programMentor(ayse.getId(), ProgramMentorStatus.ACCEPTED),
				programMentor(pendingMentor.getId(), ProgramMentorStatus.PENDING)));
		lenient().when(appUserRepository.findAllById(List.of(ayse.getId()))).thenReturn(List.of(ayse));
	}

	@Test
	void postsAndNotifiesEveryTeamMemberAndAcceptedMentor() {
		when(programService.requireManagedProgram(coordinator.getId(), program.getId())).thenReturn(program);
		when(announcementRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(appUserRepository.findById(coordinator.getId())).thenReturn(Optional.of(coordinator));

		ProgramAnnouncementResponse response = service.post(coordinator.getId(), program.getId(),
				new PostProgramAnnouncementRequest(null, "  Ürün-Pazar Uyumu Atölyesi bugün 16:00'da  "));

		assertThat(response.body()).isEqualTo("Ürün-Pazar Uyumu Atölyesi bugün 16:00'da");
		assertThat(response.authorName()).isEqualTo("Elif Demir");
		assertThat(response.teamCount()).isEqualTo(1);
		assertThat(response.mentorCount()).isEqualTo(1);
		verify(notificationService).create(eq(deniz), eq(NotificationCategory.GENERAL), eq("METU Stars 2026 — Duyuru"),
				anyString(), eq("/messages"));
		verify(notificationService).create(eq(ayse), eq(NotificationCategory.GENERAL), anyString(), anyString(),
				eq("/mentor-dashboard/messages"));
		verify(notificationService, never()).create(eq(pendingMentor), any(), anyString(), anyString(), anyString());
	}

	@Test
	void shortensLongAnnouncementsInTheNotification() {
		when(programService.requireManagedProgram(coordinator.getId(), program.getId())).thenReturn(program);
		when(announcementRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.post(coordinator.getId(), program.getId(), new PostProgramAnnouncementRequest(null, "a".repeat(300)));

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(notificationService).create(eq(deniz), any(), anyString(), body.capture(), anyString());
		assertThat(body.getValue()).hasSize(140).endsWith("…");
	}

	@Test
	void aTeamMemberReadsTheirProgramsAnnouncements() {
		ProgramAnnouncement announcement = announcement();
		when(appUserRepository.findById(deniz.getId())).thenReturn(Optional.of(deniz));
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(lumen.getId()))
				.thenReturn(List.of(startup(lumen.getId())));
		when(programRepository.findAllById(List.of(program.getId()))).thenReturn(List.of(program));
		when(announcementRepository.findByProgramIdInOrderByCreatedAtDesc(Set.of(program.getId())))
				.thenReturn(List.of(announcement));
		when(appUserRepository.findAllById(List.of(coordinator.getId()))).thenReturn(List.of(coordinator));

		List<ProgramAnnouncementResponse> mine = service.mine(deniz.getId());

		assertThat(mine).singleElement().satisfies(item -> {
			assertThat(item.programName()).isEqualTo("METU Stars 2026");
			assertThat(item.authorName()).isEqualTo("Elif Demir");
		});
	}

	@Test
	void someoneOutsideAnyProgramReadsNothing() {
		AppUser loner = user("Can Er", UserRole.MENTEE, null);
		when(appUserRepository.findById(loner.getId())).thenReturn(Optional.of(loner));

		assertThat(service.mine(loner.getId())).isEmpty();
	}

	private ProgramAnnouncement announcement() {
		ProgramAnnouncement announcement = new ProgramAnnouncement();
		announcement.setId(UUID.randomUUID());
		announcement.setProgramId(program.getId());
		announcement.setAuthorId(coordinator.getId());
		announcement.setBody("Duyuru");
		announcement.setCreatedAt(Instant.now());
		return announcement;
	}

	private ProgramStartup startup(UUID organizationId) {
		ProgramStartup startup = new ProgramStartup();
		startup.setProgramId(program.getId());
		startup.setOrganizationId(organizationId);
		return startup;
	}

	private ProgramMentor programMentor(UUID mentorId, ProgramMentorStatus status) {
		ProgramMentor row = new ProgramMentor();
		row.setProgramId(program.getId());
		row.setMentorId(mentorId);
		row.setStatus(status);
		return row;
	}

	private static Organization organization() {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName("Lumen");
		return organization;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("METU Stars 2026");
		return program;
	}

	private static AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setRole(role);
		user.setOrganization(organization);
		return user;
	}
}
