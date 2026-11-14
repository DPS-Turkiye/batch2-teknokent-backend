package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.transaction.PlatformTransactionManager;

import com.dps.teknokent.domain.MatchNeedSource;
import com.dps.teknokent.domain.MentorMatchingMethod;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.StartupMaturity;
import com.dps.teknokent.domain.StartupSector;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorRecommendationResult;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.Texts;

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
	@Mock
	private PlatformTransactionManager transactionManager;
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
	void aMatchFromAnotherProgramNeitherCountsHereNorKeepsTheTeamFromWaiting() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		Organization vega = team("Vega", List.of());
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID)))
				.thenReturn(List.of(startup(terra, 2), startup(vega, 1)));
		when(organizationRepository.findAllById(any())).thenReturn(List.of(terra, vega));
		Program kulucka = new Program();
		kulucka.setId(UUID.randomUUID());
		Mentorship elsewhere = mentorship(terra, mentor("Ayşe Demir"), MentorshipStatus.ACTIVE);
		elsewhere.setProgram(kulucka);
		Mentorship here = mentorship(vega, mentor("Mert Kaya"), MentorshipStatus.ACTIVE);
		here.setProgram(program());
		when(mentorshipRepository.findByOrganizationIdIn(any())).thenReturn(List.of(elsewhere, here));
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

		var overview = service.overview(ELIF, PROGRAM_ID);

		assertThat(overview.waiting()).extracting(team -> team.name()).containsExactly("Terra");
		assertThat(overview.active()).extracting(match -> match.teamName()).containsExactly("Vega");
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

	// --- Suggestions: who is offered for a team, and how the team is described to the ranker ---

	@Test
	void suggestionsRankThePoolAndShowEachMentorsLoadAgainstTheirCapacity() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		terra.setSector(StartupSector.FINTECH);
		terra.setMaturity(StartupMaturity.MVP);
		terra.setDescription("Ödeme altyapısı");
		AppUser nihan = mentor("Nihan Öztürk");
		AppUser kerem = mentor("Kerem Sarı");
		AppUser alreadyWithTheTeam = mentor("Ayşe Demir");
		AppUser notAcceptedYet = mentor("Mert Kaya");
		stubTeam(terra);
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(
				poolMembership(nihan, ProgramMentorStatus.ACCEPTED),
				poolMembership(kerem, ProgramMentorStatus.ACCEPTED),
				poolMembership(alreadyWithTheTeam, ProgramMentorStatus.ACCEPTED),
				poolMembership(notAcceptedYet, ProgramMentorStatus.PENDING)));
		when(mentorshipRepository.findByOrganizationIdIn(List.of(terra.getId()))).thenReturn(List.of(
				mentorship(terra, alreadyWithTheTeam, MentorshipStatus.ACTIVE),
				mentorship(terra, kerem, MentorshipStatus.COMPLETED)));
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(terra.getId()))
				.thenReturn(List.of(request(terra, kerem, "Pazarlama stratejisi", MentorshipRequestStatus.PENDING)));
		List<UUID> offered = List.of(nihan.getId(), kerem.getId());
		when(recommendationService.recommendAmong(any(), eq(offered))).thenReturn(new MentorRecommendationResult(
				MentorMatchingMethod.PGVECTOR_SEMANTIC, "matcher-v2", Instant.now(), List.of(
						profile(nihan, "Haftada 2 saat", List.of("Pazarlama deneyimi")),
						profile(kerem, " ", null))));
		when(recommendationService.poolProfiles(offered)).thenReturn(List.of(
				profile(nihan, "Haftada 2 saat", List.of("Pazarlama deneyimi")),
				profile(kerem, null, List.of("Satış deneyimi"))));
		when(mentorshipRepository.countOrganizationsByMentor(eq(offered), any()))
				.thenReturn(List.of(load(nihan.getId(), 3)));
		MentorProfile ownLimit = new MentorProfile();
		ownLimit.setId(nihan.getId());
		ownLimit.setMenteeCapacity(3);
		MentorProfile noLimit = new MentorProfile();
		noLimit.setId(kerem.getId());
		when(mentorProfileRepository.findAllById(offered)).thenReturn(List.of(ownLimit, noLimit));

		var suggestions = service.suggestions(ELIF, PROGRAM_ID, terra.getId());

		assertThat(suggestions.method()).isEqualTo("PGVECTOR_SEMANTIC");
		assertThat(suggestions.recommended()).extracting(candidate -> candidate.mentorId())
				.containsExactly(nihan.getId(), kerem.getId());
		var first = suggestions.recommended().getFirst();
		assertThat(first.fullName()).isEqualTo("Nihan Öztürk");
		assertThat(first.title()).isEqualTo("CTO");
		assertThat(first.specialties()).containsExactly("Pazarlama");
		assertThat(first.availability()).isEqualTo("Haftada 2 saat");
		assertThat(first.activeTeams()).isEqualTo(3);
		assertThat(first.capacity()).isEqualTo(3);
		assertThat(first.reasons()).containsExactly("Pazarlama deneyimi");
		var second = suggestions.recommended().get(1);
		assertThat(second.availability()).isNull();
		assertThat(second.activeTeams()).isZero();
		assertThat(second.capacity()).isEqualTo(4);
		assertThat(second.reasons()).isEmpty();
		// The whole pool is for picking by hand: alphabetical, and without the ranking's reasons.
		assertThat(suggestions.pool()).extracting(candidate -> candidate.fullName())
				.containsExactly("Kerem Sarı", "Nihan Öztürk");
		assertThat(suggestions.pool()).allSatisfy(candidate -> assertThat(candidate.reasons()).isEmpty());
		assertThat(suggestions.pool()).extracting(candidate -> candidate.availability())
				.containsExactly(null, "Haftada 2 saat");

		ArgumentCaptor<RecommendMentorsRequest> asked = ArgumentCaptor.forClass(RecommendMentorsRequest.class);
		verify(recommendationService).recommendAmong(asked.capture(), eq(offered));
		assertThat(asked.getValue().supportArea()).isEqualTo("Pazarlama stratejisi");
		assertThat(asked.getValue().summary()).isEqualTo("Pazarlama stratejisi konusunda destek");
		assertThat(asked.getValue().startupSector()).isEqualTo("FinTech");
		assertThat(asked.getValue().startupStage()).isEqualTo("MVP");
		assertThat(asked.getValue().startupSummary()).isEqualTo("Ödeme altyapısı");
	}

	@Test
	void aTeamWithoutARequestIsDescribedToTheRankerByItsProfile() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization lumen = team("Lumen", List.of("Finansal modelleme", "Satış"));
		lumen.setStatusSummary("  ");
		lumen.setDescription("Tarım IoT");
		AppUser nihan = mentor("Nihan Öztürk");
		stubTeamAndPool(lumen, nihan);
		when(recommendationService.recommendAmong(any(), eq(List.of(nihan.getId()))))
				.thenReturn(new MentorRecommendationResult(MentorMatchingMethod.DETERMINISTIC, null, Instant.now(),
						List.of()));

		var suggestions = service.suggestions(ELIF, PROGRAM_ID, lumen.getId());

		assertThat(suggestions.method()).isEqualTo("DETERMINISTIC");
		assertThat(suggestions.recommended()).isEmpty();
		ArgumentCaptor<RecommendMentorsRequest> asked = ArgumentCaptor.forClass(RecommendMentorsRequest.class);
		verify(recommendationService).recommendAmong(asked.capture(), any());
		assertThat(asked.getValue().supportArea()).isEqualTo("Finansal modelleme, Satış");
		assertThat(asked.getValue().summary()).isEqualTo("Tarım IoT");
		assertThat(asked.getValue().startupSector()).isNull();
		assertThat(asked.getValue().startupStage()).isNull();
	}

	@Test
	void aTeamThatSaidNothingAboutItsNeedIsRankedForGeneralMentoringUnderItsName() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization kavun = team("Kavun", List.of());
		kavun.setSupportAreas(null);
		AppUser nihan = mentor("Nihan Öztürk");
		stubTeamAndPool(kavun, nihan);
		when(recommendationService.recommendAmong(any(), eq(List.of(nihan.getId()))))
				.thenReturn(new MentorRecommendationResult(MentorMatchingMethod.DETERMINISTIC, null, Instant.now(),
						List.of()));

		service.suggestions(ELIF, PROGRAM_ID, kavun.getId());

		ArgumentCaptor<RecommendMentorsRequest> asked = ArgumentCaptor.forClass(RecommendMentorsRequest.class);
		verify(recommendationService).recommendAmong(asked.capture(), any());
		assertThat(asked.getValue().supportArea()).isEqualTo("Genel mentorluk");
		assertThat(asked.getValue().summary()).isEqualTo("Kavun");
	}

	@Test
	void thereIsNothingToSuggestWhenEveryPoolMentorAlreadyWorksWithTheTeam() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		AppUser nihan = mentor("Nihan Öztürk");
		stubTeamAndPool(terra, nihan);
		when(mentorshipRepository.findByOrganizationIdIn(List.of(terra.getId())))
				.thenReturn(List.of(mentorship(terra, nihan, MentorshipStatus.PLANNED)));

		var suggestions = service.suggestions(ELIF, PROGRAM_ID, terra.getId());

		assertThat(suggestions.method()).isNull();
		assertThat(suggestions.recommended()).isEmpty();
		assertThat(suggestions.pool()).isEmpty();
		verifyNoInteractions(recommendationService, mentorProfileRepository);
	}

	@Test
	void aTeamOutsideTheProgramGetsNeitherSuggestionsNorAMatch() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		UUID outsider = UUID.randomUUID();
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, outsider))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.suggestions(ELIF, PROGRAM_ID, outsider))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("not in this program");
		assertThatThrownBy(() -> service.match(ELIF, PROGRAM_ID, outsider, UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("not in this program");
		verifyNoInteractions(organizationRepository, recommendationService, notificationService);
		verify(mentorshipRepository, never()).save(any());
	}

	// --- Matching ---

	@Test
	void aPoolMembershipThatIsNotAcceptedYetDoesNotMakeAMentorMatchable() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		AppUser invited = mentor("Mert Kaya");
		stubTeam(terra);
		when(programMentorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID))
				.thenReturn(List.of(poolMembership(invited, ProgramMentorStatus.PENDING)));

		assertThatThrownBy(() -> service.match(ELIF, PROGRAM_ID, terra.getId(), invited.getId()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("mentor in this program's pool");
		verify(mentorshipRepository, never()).save(any());
	}

	@Test
	void aPoolMemberWhoNoLongerHoldsTheMentorRoleCannotBeMatched() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		AppUser formerMentor = user("Kerem Sarı", UserRole.MENTEE);
		stubTeamAndPool(terra, formerMentor);
		when(appUserRepository.findById(formerMentor.getId())).thenReturn(Optional.of(formerMentor));

		assertThatThrownBy(() -> service.match(ELIF, PROGRAM_ID, terra.getId(), formerMentor.getId()))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Mentor not found");
		verify(mentorshipRepository, never()).save(any());
	}

	@Test
	void anEndedMentorshipWithTheSameMentorDoesNotBlockMatchingThemAgain() {
		Program program = program();
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program);
		Organization lumen = team("Lumen", List.of("Finansal modelleme"));
		AppUser nihan = mentor("Nihan Öztürk");
		stubTeamAndPool(lumen, nihan);
		when(appUserRepository.findById(nihan.getId())).thenReturn(Optional.of(nihan));
		when(mentorshipRepository.findByOrganizationIdIn(List.of(lumen.getId()))).thenReturn(List.of(
				mentorship(lumen, nihan, MentorshipStatus.COMPLETED),
				mentorship(lumen, mentor("Kerem Sarı"), MentorshipStatus.ACTIVE)));
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID))).thenReturn(List.of());

		service.match(ELIF, PROGRAM_ID, lumen.getId(), nihan.getId());

		ArgumentCaptor<Mentorship> saved = ArgumentCaptor.forClass(Mentorship.class);
		verify(mentorshipRepository).save(saved.capture());
		assertThat(saved.getValue().getOrganization()).isSameAs(lumen);
		assertThat(saved.getValue().getProgram()).isSameAs(program);
		assertThat(saved.getValue().getStartDate()).isEqualTo(LocalDate.now(MeetingSlotTimes.APP_ZONE));
		assertThat(saved.getValue().getEndDate()).isNull();
		assertThat(saved.getValue().getDaysPerWeek()).isEqualTo(1);
		assertThat(saved.getValue().getGoals()).isEqualTo("Finansal modelleme");
	}

	@Test
	void matchingTellsTheMentorWhichTeamAndOnlyTheTeamsMenteesWhichMentor() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		AppUser nihan = mentor("Nihan Öztürk");
		stubTeamAndPool(terra, nihan);
		when(appUserRepository.findById(nihan.getId())).thenReturn(Optional.of(nihan));
		MentorshipRequest toNihan = request(terra, nihan, "Pazarlama", MentorshipRequestStatus.COUNTER_PROPOSED);
		MentorshipRequest alreadyAccepted = request(terra, mentor("Kerem Sarı"), "Satış",
				MentorshipRequestStatus.ACCEPTED);
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(terra.getId()))
				.thenReturn(List.of(toNihan, alreadyAccepted));
		AppUser founder = user("Mert Aydın", UserRole.MENTEE);
		AppUser advisor = user("Selin Aksoy", UserRole.ACADEMICIAN);
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(terra.getId()))
				.thenReturn(List.of(founder, advisor));
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID))).thenReturn(List.of());

		service.match(ELIF, PROGRAM_ID, terra.getId(), nihan.getId());

		verify(notificationService).create(nihan, Texts.get(nihan, "notification.matchedWithTeam.title"),
				Texts.get(nihan, "notification.matchedWithTeam.body", "METU Stars 2026", "Terra"),
				"/mentor-dashboard");
		verify(notificationService).create(founder, Texts.get(founder, "notification.mentorAssigned.title"),
				Texts.get(founder, "notification.mentorAssigned.body", "METU Stars 2026", "Nihan Öztürk"),
				"/mentorships");
		verify(notificationService, never()).create(eq(advisor), anyString(), anyString(), anyString());
		assertThat(toNihan.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		verify(mentorshipRequestRepository).save(toNihan);
		verify(mentorshipRequestRepository, never()).save(alreadyAccepted);
	}

	// --- The overview ---

	@Test
	void waitingTeamsCarryTheirSectorStageLeadAndHowLongTheyHaveWaited() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization lumen = team("Lumen", List.of("Finansal modelleme"));
		lumen.setSector(StartupSector.FINTECH);
		lumen.setMaturity(StartupMaturity.MVP);
		lumen.setFounder(user("Mert Aydın", UserRole.MENTEE));
		Organization kavun = team("Kavun", List.of());
		ProgramStartup addedAheadOfTheClock = startup(kavun, 0);
		addedAheadOfTheClock.setCreatedAt(Instant.now().plus(3, ChronoUnit.DAYS));
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID)))
				.thenReturn(List.of(addedAheadOfTheClock, startup(lumen, 5)));
		when(organizationRepository.findAllById(any())).thenReturn(List.of(lumen, kavun));
		when(mentorshipRepository.findByOrganizationIdIn(any())).thenReturn(List.of());
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

		var waiting = service.overview(ELIF, PROGRAM_ID).waiting();

		assertThat(waiting).extracting(team -> team.name()).containsExactly("Lumen", "Kavun");
		assertThat(waiting.getFirst().sector()).isEqualTo("FinTech");
		assertThat(waiting.getFirst().stage()).isEqualTo("MVP");
		assertThat(waiting.getFirst().leadName()).isEqualTo("Mert Aydın");
		// Midnight in Turkey may pass between building the fixture and the service reading today's date.
		assertThat(waiting.getFirst().waitingDays()).isBetween(5, 6);
		assertThat(waiting.getFirst().needTopic()).isEqualTo("Finansal modelleme");
		assertThat(waiting.getFirst().needText()).isEqualTo("Genel mentorluk");
		assertThat(waiting.get(1).sector()).isNull();
		assertThat(waiting.get(1).stage()).isNull();
		assertThat(waiting.get(1).leadName()).isNull();
		assertThat(waiting.get(1).waitingDays()).isZero();
	}

	@Test
	void aTeamWhoseMentorshipEndedWaitsAgainAndTheEndedMatchMovesToThePast() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		Organization terra = team("Terra", List.of());
		Organization vega = team("Vega", List.of());
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID)))
				.thenReturn(List.of(startup(terra, 9), startup(vega, 8)));
		when(organizationRepository.findAllById(any())).thenReturn(List.of(terra, vega));
		LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		Mentorship completed = mentorship(terra, mentor("Ayşe Demir"), MentorshipStatus.COMPLETED);
		completed.setStartDate(today.minusDays(90));
		completed.setEndDate(today.minusDays(10));
		Mentorship cancelled = mentorship(terra, mentor("Kerem Sarı"), MentorshipStatus.CANCELLED);
		cancelled.setStartDate(today.minusDays(30));
		Mentorship planned = mentorship(vega, mentor("Mert Kaya"), MentorshipStatus.PLANNED);
		planned.setStartDate(today.minusDays(2));
		Mentorship active = mentorship(vega, mentor("Nihan Öztürk"), MentorshipStatus.ACTIVE);
		active.setStartDate(today);
		when(mentorshipRepository.findByOrganizationIdIn(any()))
				.thenReturn(List.of(completed, cancelled, planned, active));
		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(terra.getId()))
				.thenReturn(List.of());

		var overview = service.overview(ELIF, PROGRAM_ID);

		assertThat(overview.waiting()).extracting(team -> team.name()).containsExactly("Terra");
		assertThat(overview.active()).extracting(match -> match.mentorName())
				.containsExactly("Nihan Öztürk", "Mert Kaya");
		assertThat(overview.past()).extracting(match -> match.mentorName())
				.containsExactly("Kerem Sarı", "Ayşe Demir");
		assertThat(overview.past().get(1).status()).isEqualTo(MentorshipStatus.COMPLETED);
		assertThat(overview.past().get(1).endDate()).isEqualTo(today.minusDays(10));
		assertThat(overview.past().get(1).organizationId()).isEqualTo(terra.getId());
	}

	@Test
	void aProgramWithNoTeamsHasAnEmptyOverview() {
		when(coordinatorProgramService.requireManagedProgram(ELIF, PROGRAM_ID)).thenReturn(program());
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM_ID))).thenReturn(List.of());

		var overview = service.overview(ELIF, PROGRAM_ID);

		assertThat(overview.waiting()).isEmpty();
		assertThat(overview.active()).isEmpty();
		assertThat(overview.past()).isEmpty();
		verifyNoInteractions(mentorshipRepository);
	}

	private void stubTeam(Organization team) {
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, team.getId()))
				.thenReturn(Optional.of(startup(team, 4)));
		when(organizationRepository.findById(team.getId())).thenReturn(Optional.of(team));
	}

	private static ProgramMentor poolMembership(AppUser mentor, ProgramMentorStatus status) {
		ProgramMentor membership = new ProgramMentor();
		membership.setMentorId(mentor.getId());
		membership.setStatus(status);
		return membership;
	}

	private static MentorProfileResponse profile(AppUser mentor, String availability, List<String> reasons) {
		return new MentorProfileResponse(mentor.getId(), mentor.getFullName(), "mentor@tekno.com", "M", "CTO", "Acme",
				availability, "", "", "", "", "", "", "", "", List.of("Pazarlama"), reasons, List.of());
	}

	private static MentorCount load(UUID mentorId, long teams) {
		return new MentorCount() {
			@Override
			public UUID getMentorId() {
				return mentorId;
			}

			@Override
			public Long getTotal() {
				return teams;
			}
		};
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
		mentorship.setStartDate(java.time.LocalDate.now(MeetingSlotTimes.APP_ZONE));
		return mentorship;
	}

	private static MentorshipRequest request(Organization team, AppUser mentor, String area,
			MentorshipRequestStatus status) {
		MentorshipRequest request = new MentorshipRequest();
		request.setOrganization(team);
		request.setMentor(mentor);
		request.setSupportArea(area);
		request.setSummary(area + " konusunda destek");
		request.setStatus(status);
		return request;
	}
}
