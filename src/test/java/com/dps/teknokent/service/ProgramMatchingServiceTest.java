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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MatchNeedSource;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class ProgramMatchingServiceTest {

	@Mock
	private CoordinatorProgramService coordinatorProgramService;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private MentorRecommendationService recommendationService;
	@Mock
	private NotificationService notificationService;
	@InjectMocks
	private ProgramMatchingService service;

	private static final UUID ELIF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

	@Test
	void waitingTeamsShowTheirNeedFromARequestOrTheirProfileAndTheOldestComesFirst() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		Organization lumen = team("Lumen", List.of("Finansal modelleme"));
		Organization kavun = team("Kavun", List.of());
		Organization vega = team("Vega", List.of());
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID))).thenReturn(List.of(
				startup(lumen, 3), startup(terra, 4), startup(kavun, 2), startup(vega, 1)));
		when(organizationRepository.findAllById(any())).thenReturn(List.of(terra, lumen, kavun, vega));
		when(mentorshipRepository.findByOrganizationIdIn(any()))
				.thenReturn(List.of(mentorship(vega, mentor("Ayşe Demir"), MentorshipStatus.ACTIVE)));
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(terra.getId()))
				.thenReturn(List.of(request(terra, null, "Pazarlama stratejisi", MentorshipRequestStatus.PENDING)));

		var overview = service.overview(ELIF, PROGRAM_ID);

		assertThat(overview.waiting()).extracting(team -> team.name()).containsExactly("Terra", "Lumen", "Kavun");
		assertThat(overview.waiting().get(0).needSource()).isEqualTo(MatchNeedSource.REQUEST);
		assertThat(overview.waiting().get(0).needTopic()).isEqualTo("Pazarlama stratejisi");
		assertThat(overview.waiting().get(1).needSource()).isEqualTo(MatchNeedSource.PROFILE);
		assertThat(overview.waiting().get(2).needSource()).isEqualTo(MatchNeedSource.NONE);
		assertThat(overview.active()).extracting(match -> match.teamName()).containsExactly("Vega");
	}

	@Test
	void matchingStartsTheMentorshipClosesOpenRequestsAndTellsBothSides() {
		Program program = program();
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program);
		Organization terra = team("Terra", List.of());
		AppUser nihan = mentor("Nihan Öztürk");
		AppUser kerem = mentor("Kerem Sarı");
		stubTeamAndPool(terra, nihan);
		when(appUserRepository.findById(nihan.getId())).thenReturn(Optional.of(nihan));
		when(mentorshipRepository.findByOrganizationIdIn(List.of(terra.getId()))).thenReturn(List.of());
		MentorshipRequest toNihan = request(terra, nihan, "Pazarlama stratejisi", MentorshipRequestStatus.PENDING);
		MentorshipRequest toKerem = request(terra, kerem, "Satış", MentorshipRequestStatus.MORE_INFO_REQUESTED);
		MentorshipRequest declined = request(terra, kerem, "Hukuk", MentorshipRequestStatus.DECLINED);
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(terra.getId()))
				.thenReturn(List.of(toNihan, toKerem, declined));
		AppUser founder = user("Mert Aydın", UserRole.MENTEE);
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(terra.getId())).thenReturn(List.of(founder));
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID))).thenReturn(List.of());

		service.match(ELIF, PROGRAM_ID, terra.getId(), nihan.getId());

		ArgumentCaptor<Mentorship> saved = ArgumentCaptor.forClass(Mentorship.class);
		verify(mentorshipRepository).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(MentorshipStatus.ACTIVE);
		assertThat(saved.getValue().getMentor()).isEqualTo(nihan);
		assertThat(saved.getValue().getGoals()).isEqualTo("Pazarlama stratejisi");
		assertThat(toNihan.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(toKerem.getStatus()).isEqualTo(MentorshipRequestStatus.WITHDRAWN);
		assertThat(declined.getStatus()).isEqualTo(MentorshipRequestStatus.DECLINED);
		verify(notificationService).create(eq(nihan), eq("Yeni mentorluk eşleşmesi"), anyString(), anyString());
		verify(notificationService).create(eq(founder), eq("Mentorunuz belirlendi"), anyString(), anyString());
	}

	@Test
	void onlyAPoolMentorWhoIsNotAlreadyWithTheTeamCanBeMatched() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		AppUser nihan = mentor("Nihan Öztürk");
		stubTeamAndPool(terra, nihan);

		assertThatThrownBy(() -> service.match(ELIF, PROGRAM_ID, terra.getId(), UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class);

		when(appUserRepository.findById(nihan.getId())).thenReturn(Optional.of(nihan));
		when(mentorshipRepository.findByOrganizationIdIn(List.of(terra.getId())))
				.thenReturn(List.of(mentorship(terra, nihan, MentorshipStatus.ACTIVE)));
		assertThatThrownBy(() -> service.match(ELIF, PROGRAM_ID, terra.getId(), nihan.getId()))
				.isInstanceOf(ConflictException.class);
		verify(mentorshipRepository, never()).save(any());
	}

	@Test
	void startupsWaitingForApprovalAreNeitherListedNorMatchable() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		Organization kargo = team("Kargo AI", List.of());
		kargo.setApprovalStatus(UserAccountStatus.PENDING);
		Organization dongu = team("Döngü", List.of());
		AppUser waitingFounder = user("Ayşe Demir", UserRole.MENTEE);
		waitingFounder.setStatus(UserAccountStatus.PENDING);
		dongu.setFounder(waitingFounder);
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID)))
				.thenReturn(List.of(startup(terra, 3), startup(kargo, 2), startup(dongu, 1)));
		when(organizationRepository.findAllById(any())).thenReturn(List.of(terra, kargo, dongu));
		when(mentorshipRepository.findByOrganizationIdIn(any())).thenReturn(List.of());
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

		assertThat(service.overview(ELIF, PROGRAM_ID).waiting()).extracting(team -> team.name())
				.containsExactly("Terra");

		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, kargo.getId()))
				.thenReturn(Optional.of(startup(kargo, 2)));
		when(organizationRepository.findById(kargo.getId())).thenReturn(Optional.of(kargo));
		assertThatThrownBy(() -> service.match(ELIF, PROGRAM_ID, kargo.getId(), UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class);
		verify(mentorshipRepository, never()).save(any());
	}

	private void stubTeamAndPool(Organization team, AppUser mentor) {
		ProgramStartup startup = startup(team, 4);
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, team.getId()))
				.thenReturn(Optional.of(startup));
		when(organizationRepository.findById(team.getId())).thenReturn(Optional.of(team));
		ProgramMentor membership = new ProgramMentor();
		membership.setMentorId(mentor.getId());
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(membership));
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setDefaultMentorCapacity(4);
		return program;
	}

	private static Organization team(String name, List<String> supportAreas) {
		Organization team = new Organization();
		team.setId(UUID.randomUUID());
		team.setName(name);
		team.setSupportAreas(new java.util.ArrayList<>(supportAreas));
		return team;
	}

	private static ProgramStartup startup(Organization team, int daysAgo) {
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(PROGRAM_ID);
		startup.setOrganizationId(team.getId());
		startup.setCreatedAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
		return startup;
	}

	private static AppUser mentor(String name) {
		return user(name, UserRole.MENTOR);
	}

	private static AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setRole(role);
		return user;
	}

	private static Mentorship mentorship(Organization team, AppUser mentor, MentorshipStatus status) {
		Mentorship mentorship = new Mentorship();
		mentorship.setId(UUID.randomUUID());
		mentorship.setOrganization(team);
		mentorship.setMentor(mentor);
		mentorship.setStatus(status);
		mentorship.setStartDate(java.time.LocalDate.now());
		return mentorship;
	}

	private static MentorshipRequest request(Organization team, AppUser mentor, String area, MentorshipRequestStatus status) {
		MentorshipRequest request = new MentorshipRequest();
		request.setOrganization(team);
		request.setMentor(mentor);
		request.setSupportArea(area);
		request.setSummary(area + " konusunda destek");
		request.setStatus(status);
		return request;
	}
}
