package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeKind;
import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.PeerExperienceKind;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.PeerTopicLevel;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.CreatePeerMentoringRequest;
import com.dps.teknokent.dto.requests.PeerExperienceCardRequest;
import com.dps.teknokent.dto.requests.SavePeerMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilityRuleResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.PeerCategoryCountResponse;
import com.dps.teknokent.dto.responses.PeerExperienceCardResponse;
import com.dps.teknokent.dto.responses.PeerMentorCardResponse;
import com.dps.teknokent.dto.responses.PeerMentorDetailResponse;
import com.dps.teknokent.dto.responses.PeerMentorProfileResponse;
import com.dps.teknokent.dto.responses.PeerMentoringRequestResponse;
import com.dps.teknokent.dto.responses.PublicBadgeResponse;
import com.dps.teknokent.dto.responses.StudentProfileResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.MentorExperience;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.PeerMentorDraftRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

class PeerMentoringServiceTest {

	private static final UUID PROGRAM_ID = UUID.randomUUID();

	private final DomainLookupService lookup = mock(DomainLookupService.class);
	private final InstitutionDirectoryService directory = mock(InstitutionDirectoryService.class);
	private final InstitutionPersonRepository people = mock(InstitutionPersonRepository.class);
	private final MenteeProgramService programs = mock(MenteeProgramService.class);
	private final MentorProfileService profiles = mock(MentorProfileService.class);
	private final MentorshipRequestService requestService = mock(MentorshipRequestService.class);
	private final MentorshipRequestRepository requests = mock(MentorshipRequestRepository.class);
	private final PersonalTeamService personalTeams = mock(PersonalTeamService.class);
	private final MentorProfileRepository profileRepository = mock(MentorProfileRepository.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final BadgeService badges = mock(BadgeService.class);
	private final ProgramPeerCategoryService peerCategories = mock(ProgramPeerCategoryService.class);
	private final ProgramMentorRepository programMentors = mock(ProgramMentorRepository.class);
	private final ProgramRepository programRepository = mock(ProgramRepository.class);
	private final MentorAvailabilitySlotRepository slots = mock(MentorAvailabilitySlotRepository.class);
	private final PeerMentorDraftRepository drafts = mock(PeerMentorDraftRepository.class);
	private PeerMentoringService service;
	private List<MentorProfileResponse> programProfiles;
	private MentorProfile published;

	private AppUser student;
	private AppUser peerMentor;
	private AppUser teknokentMentor;

	@BeforeEach
	void setUp() {
		service = new PeerMentoringService(lookup, directory, people, programs, profiles, requestService, requests,
				personalTeams, profileRepository, users, badges, peerCategories, programMentors, programRepository, slots, drafts);
		student = user("Ayşe Yılmaz", Set.of(IdentityKind.STUDENT), UserRole.MENTEE);
		student.setProgramId(PROGRAM_ID);
		peerMentor = user("Zeynep Demir", Set.of(IdentityKind.STUDENT), UserRole.MENTEE, UserRole.MENTOR);
		teknokentMentor = user("QA Test Mentor", Set.of(), UserRole.MENTOR);
		when(programs.programOf(student)).thenReturn(Optional.of(PROGRAM_ID));
		Set<UUID> accepted = Set.of(peerMentor.getId(), teknokentMentor.getId());
		when(programs.acceptedMentorIds(PROGRAM_ID)).thenReturn(accepted);
		programProfiles = List.of(
				profile(peerMentor, List.of("Kariyer ve Staj", "TECHNICAL_DIGITAL", "Bambaşka"), "CV ve staj", 3, 1,
						List.of("cv", "interview"), new StudentProfileResponse("Bilgisayar Mühendisliği", "4",
								List.of("CENG 315"), List.of("Kampüs uygulaması"), "TU Delft")),
				profile(teknokentMentor, List.of("Fintech"), "Startup büyütme", null, 0));
		when(profiles.findAmongExcept(any(), eq(student.getId()))).thenAnswer(invocation -> {
			Set<UUID> among = invocation.getArgument(0);
			return programProfiles.stream().filter(profile -> among.contains(profile.id())).toList();
		});
		when(people.findFirstByUserIdOrderBySyncedAtDesc(peerMentor.getId()))
				.thenReturn(Optional.of(person("Bilgisayar Mühendisliği", 4)));
		when(people.findFirstByUserIdOrderBySyncedAtDesc(student.getId()))
				.thenReturn(Optional.of(person("Bilgisayar Mühendisliği", 3)));
		when(people.findFirstByUserIdOrderBySyncedAtDesc(teknokentMentor.getId())).thenReturn(Optional.empty());
		activate(EnumSet.allOf(PeerTopicCategory.class));
		ProgramMentor membership = new ProgramMentor();
		membership.setProgramId(PROGRAM_ID);
		membership.setMentorId(peerMentor.getId());
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentors.findByMentorIdAndStatusOrderByCreatedAtDesc(peerMentor.getId(), ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(membership));
		Program running = new Program();
		running.setId(PROGRAM_ID);
		running.setStatus(ProgramStatus.ACTIVE);
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(running));
		published = MentorProfile.blankFor(peerMentor, 1);
		when(profileRepository.findById(peerMentor.getId())).thenReturn(Optional.of(published));
		when(badges.publicOf(peerMentor.getId())).thenReturn(List.of(
				new PublicBadgeResponse(BadgeCode.SESSIONS_10, BadgeKind.ACHIEVEMENT, PROGRAM_ID, Instant.now())));
	}

	@Test
	void theWizardPublishesAProfileFromABlankRowWithTheStudentsOdtuDetails() {
		when(profileRepository.findById(peerMentor.getId())).thenReturn(Optional.empty());
		when(profileRepository.findMaxSortOrder()).thenReturn(4);
		when(profileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		PeerMentorProfileResponse saved = service.saveProfile(peerMentor.getId(), new SavePeerMentorProfileRequest(
				null,
				List.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.CAREER_INTERNSHIP,
						PeerTopicCategory.INTERNATIONAL),
				List.of(" CV hazırlama ", ""),
				List.of("IE 251"),
				List.of("Tedarik zinciri simülasyonu"),
				"  ",
				"  Staj başvurusu ve Erasmus planlamasında yardımcı olabilirim.  ",
				"https://linkedin.com/in/zeynep",
				null,
				null));

		ArgumentCaptor<MentorProfile> profile = ArgumentCaptor.forClass(MentorProfile.class);
		verify(profileRepository).save(profile.capture());
		verify(drafts).deleteById(peerMentor.getId());
		assertThat(profile.getValue().getUser()).isSameAs(peerMentor);
		assertThat(profile.getValue().getSortOrder()).isEqualTo(5);
		assertThat(profile.getValue().getSpecialties()).containsExactly("CAREER_INTERNSHIP", "INTERNATIONAL");
		assertThat(profile.getValue().getOfferings()).containsExactly("CV hazırlama");
		assertThat(profile.getValue().getErasmus()).isNull();
		assertThat(profile.getValue().getDepartment()).isEqualTo("Bilgisayar Mühendisliği");
		assertThat(profile.getValue().getStudyYear()).isEqualTo("4");
		assertThat(peerMentor.getLinkedinUrl()).isEqualTo("https://linkedin.com/in/zeynep");
		assertThat(saved.categories())
				.containsExactly(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.INTERNATIONAL);
		assertThat(saved.helpWith()).isEqualTo("Staj başvurusu ve Erasmus planlamasında yardımcı olabilirim.");
		assertThat(saved.classYear()).isEqualTo(4);
	}

	@Test
	void aPeerMentorWithoutAProfileYetGetsNotFound() {
		when(profileRepository.findById(peerMentor.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.profile(peerMentor.getId())).isInstanceOf(ResourceNotFoundException.class);
	}

	// D4 seam: a revoked badge takes MENTOR away, and with it the mentor side.
	@Test
	void aStudentWithoutMentorCannotUseTheMentorSide() {
		assertThatThrownBy(() -> service.requests(student.getId(), UserRole.MENTOR))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> service.profile(student.getId())).isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void aStudentSeesTheirProgramsPeerMentorsButNeverTeknokentMentors() {
		List<PeerMentorCardResponse> cards = service.mentors(student.getId(), null, null, null, false);

		assertThat(cards).singleElement().satisfies(card -> {
			assertThat(card.mentorId()).isEqualTo(peerMentor.getId());
			assertThat(card.fullName()).isEqualTo("Zeynep Demir");
			assertThat(card.department()).isEqualTo("Bilgisayar Mühendisliği");
			assertThat(card.classYear()).isEqualTo(4);
			assertThat(card.categories())
					.containsExactly(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.TECHNICAL_DIGITAL);
			assertThat(card.helpWith()).isEqualTo("CV ve staj");
			assertThat(card.recommendationReasons()).hasSize(1);
			assertThat(card.capacityFull()).isFalse();
			assertThat(card.badges()).extracting(PublicBadgeResponse::code).containsExactly(BadgeCode.SESSIONS_10);
		});
	}

	@Test
	void theDirectoryFiltersByCategoryAndText() {
		assertThat(service.mentors(student.getId(), PeerTopicCategory.INTERNATIONAL, null, null, false)).isEmpty();
		assertThat(service.mentors(student.getId(), PeerTopicCategory.CAREER_INTERNSHIP, null, null, false)).hasSize(1);
		assertThat(service.mentors(student.getId(), null, "zeynep", null, false)).hasSize(1);
		assertThat(service.mentors(student.getId(), null, "erasmus", null, false)).isEmpty();
	}

	@Test
	void onlyTheProgramsActiveCategoriesShowAndAMentorWithNoneOfThemIsLeftOut() {
		activate(EnumSet.of(PeerTopicCategory.TECHNICAL_DIGITAL));

		assertThat(service.mentors(student.getId(), null, null, null, false)).singleElement()
				.satisfies(card -> assertThat(card.categories()).containsExactly(PeerTopicCategory.TECHNICAL_DIGITAL));
		assertThat(service.mentors(student.getId(), PeerTopicCategory.CAREER_INTERNSHIP, null, null, false)).isEmpty();
		assertThat(service.categories(student.getId(), UserRole.MENTEE))
				.containsExactly(PeerTopicCategory.TECHNICAL_DIGITAL);

		activate(EnumSet.of(PeerTopicCategory.INTERNATIONAL));
		assertThat(service.mentors(student.getId(), null, null, null, false)).isEmpty();
	}

	@Test
	void noCategoryIsActiveUntilTheCoordinatorTurnsOneOn() {
		activate(EnumSet.noneOf(PeerTopicCategory.class));

		assertThat(service.categories(student.getId(), UserRole.MENTEE)).isEmpty();
		assertThat(service.mentors(student.getId(), null, null, null, false)).isEmpty();
	}

	@Test
	void aPeerMentorUsesTheCategoriesOfTheProgramsTheyMentorIn() {
		when(peerCategories.activeIn(List.of(PROGRAM_ID)))
				.thenReturn(EnumSet.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.ACADEMIC_DEVELOPMENT));

		assertThat(service.categories(peerMentor.getId(), UserRole.MENTOR))
				.containsExactly(PeerTopicCategory.ACADEMIC_DEVELOPMENT, PeerTopicCategory.CAREER_INTERNSHIP);
	}

	@Test
	void aRequestInACategoryTheProgramDoesNotUseIsRefusedBeforeAnyTeamIsMade() {
		activate(EnumSet.of(PeerTopicCategory.TECHNICAL_DIGITAL));

		assertThatThrownBy(() -> service.create(student.getId(), new CreatePeerMentoringRequest(null,
				peerMentor.getId(), PeerTopicCategory.CAREER_INTERNSHIP, "Staj başvurusu için CV'me bakar mısın?")))
				.isInstanceOf(InvalidRequestException.class);
		verify(personalTeams, never()).ensureFor(any());
		verify(requestService, never()).create(any());
	}

	@Test
	void theWizardCannotPublishACategoryTheMentorsProgramsDoNotUse() {
		when(peerCategories.activeIn(List.of(PROGRAM_ID))).thenReturn(EnumSet.of(PeerTopicCategory.CAREER_INTERNSHIP));

		assertThatThrownBy(() -> service.saveProfile(peerMentor.getId(), new SavePeerMentorProfileRequest(null,
				List.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.INTERNATIONAL), List.of(), List.of(),
				List.of(), null, "Staj başvurusunda yardımcı olabilirim.", null, null, null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(profileRepository, never()).save(any());
	}

	@Test
	void aRequestInACategoryTheMentorDoesNotOfferIsRefused() {
		activate(EnumSet.of(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.INTERNATIONAL));

		assertThatThrownBy(() -> service.create(student.getId(), new CreatePeerMentoringRequest(null,
				peerMentor.getId(), PeerTopicCategory.INTERNATIONAL, "Erasmus başvurusu için yardım isterim, lütfen.")))
				.isInstanceOf(InvalidRequestException.class);
		verify(personalTeams, never()).ensureFor(any());
		verify(requestService, never()).create(any());
	}

	@Test
	void aCategoryTurnedOffDropsOutOfTheMentorsOwnProfileSoTheWizardCanSaveAgain() {
		MentorProfile stored = MentorProfile.blankFor(peerMentor, 1);
		stored.setSpecialties(new java.util.ArrayList<>(List.of("CAREER_INTERNSHIP", "INTERNATIONAL")));
		when(profileRepository.findById(peerMentor.getId())).thenReturn(Optional.of(stored));
		when(peerCategories.activeIn(List.of(PROGRAM_ID))).thenReturn(EnumSet.of(PeerTopicCategory.CAREER_INTERNSHIP));

		assertThat(service.profile(peerMentor.getId()).categories()).containsExactly(PeerTopicCategory.CAREER_INTERNSHIP);
	}

	@Test
	void anArchivedProgramsCategoriesNoLongerCountForItsMentors() {
		Program archived = new Program();
		archived.setId(PROGRAM_ID);
		archived.setStatus(ProgramStatus.COMPLETED);
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(archived));
		when(peerCategories.activeIn(List.of())).thenReturn(EnumSet.noneOf(PeerTopicCategory.class));

		assertThat(service.categories(peerMentor.getId(), UserRole.MENTOR)).isEmpty();
	}

	@Test
	void aStudentInNoProgramSeesNobody() {
		when(programs.programOf(student)).thenReturn(Optional.empty());

		assertThat(service.mentors(student.getId(), null, null, null, false)).isEmpty();
	}

	@Test
	void aTeknokentMenteeCannotUsePeerMentoring() {
		AppUser founder = user("Kurucu", Set.of(), UserRole.MENTEE);

		assertThatThrownBy(() -> service.mentors(founder.getId(), null, null, null, false))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void aRequestMakesThePersonalTeamThenGoesThroughTheOrdinaryRequestFlow() {
		Organization personal = new Organization();
		personal.setId(UUID.randomUUID());
		personal.setPersonal(true);
		when(requests.findByRequesterIdOrderByCreatedAtDesc(student.getId())).thenReturn(List.of());
		when(personalTeams.ensureFor(student)).thenReturn(personal);
		UUID requestId = UUID.randomUUID();
		MentorshipRequestResponse created = mock(MentorshipRequestResponse.class);
		when(created.id()).thenReturn(requestId);
		when(requestService.create(any())).thenReturn(created);
		MentorshipRequest saved = request(requestId, peerMentor, PeerTopicCategory.CAREER_INTERNSHIP.name());
		when(requests.findById(requestId)).thenReturn(Optional.of(saved));

		PeerMentoringRequestResponse response = service.create(student.getId(), new CreatePeerMentoringRequest(null,
				peerMentor.getId(), PeerTopicCategory.CAREER_INTERNSHIP, "  Staj başvurusu için CV'me bakar mısın?  "));

		ArgumentCaptor<CreateMentorshipRequestSubmission> submission =
				ArgumentCaptor.forClass(CreateMentorshipRequestSubmission.class);
		verify(requestService).create(submission.capture());
		assertThat(submission.getValue().organizationId()).isEqualTo(personal.getId());
		assertThat(submission.getValue().requesterId()).isEqualTo(student.getId());
		assertThat(submission.getValue().mentorId()).isEqualTo(peerMentor.getId());
		assertThat(submission.getValue().supportArea()).isEqualTo("CAREER_INTERNSHIP");
		assertThat(submission.getValue().summary()).isEqualTo("Staj başvurusu için CV'me bakar mısın?");
		assertThat(response.category()).isEqualTo(PeerTopicCategory.CAREER_INTERNSHIP);
		assertThat(response.mentor().fullName()).isEqualTo("Zeynep Demir");
	}

	@Test
	void aMentorOutsideTheProgramIsRefusedBeforeAnyTeamIsMade() {
		assertThatThrownBy(() -> service.create(student.getId(), new CreatePeerMentoringRequest(null,
				teknokentMentor.getId(), PeerTopicCategory.CAREER_INTERNSHIP, "Startup fikrim için yardım isterim.")))
				.isInstanceOf(InvalidRequestException.class);
		verify(personalTeams, never()).ensureFor(any());
		verify(requestService, never()).create(any());
	}

	@Test
	void aSecondRequestToTheSameMentorWhileOneIsOpenIsRefused() {
		MentorshipRequest open = request(UUID.randomUUID(), peerMentor, "CAREER_INTERNSHIP");
		when(requests.findByRequesterIdOrderByCreatedAtDesc(student.getId())).thenReturn(List.of(open));

		assertThatThrownBy(() -> service.create(student.getId(), new CreatePeerMentoringRequest(null,
				peerMentor.getId(), PeerTopicCategory.CAREER_INTERNSHIP, "Bir sorum daha olacaktı, yardım eder misin?")))
				.isInstanceOf(ConflictException.class);
		verify(personalTeams, never()).ensureFor(any());
	}

	@Test
	void theStudentsListHoldsOnlyPeerRequests() {
		MentorshipRequest peer = request(UUID.randomUUID(), peerMentor, "INTERNATIONAL");
		MentorshipRequest startup = request(UUID.randomUUID(), teknokentMentor, "Yatırım");
		when(requests.findByRequesterIdOrderByCreatedAtDesc(student.getId())).thenReturn(List.of(peer, startup));

		assertThat(service.requests(student.getId(), UserRole.MENTEE))
				.extracting(PeerMentoringRequestResponse::category)
				.containsExactly(PeerTopicCategory.INTERNATIONAL);
	}

	@Test
	void aStudentOpensAProgramMentorsProfileWithWhatTheWizardPublishedAndTheWeeklyHours() {
		peerMentor.setLinkedinUrl("https://linkedin.com/in/zeynep");
		MentorAvailabilityRuleResponse tuesday = new MentorAvailabilityRuleResponse(UUID.randomUUID(),
				DayOfWeek.TUESDAY, LocalTime.of(18, 0), LocalTime.of(20, 0), 60);
		when(profiles.findAvailabilityRules(peerMentor.getId())).thenReturn(List.of(tuesday));

		PeerMentorDetailResponse detail = service.mentor(student.getId(), peerMentor.getId());

		assertThat(detail.mentorId()).isEqualTo(peerMentor.getId());
		assertThat(detail.fullName()).isEqualTo("Zeynep Demir");
		assertThat(detail.department()).isEqualTo("Bilgisayar Mühendisliği");
		assertThat(detail.classYear()).isEqualTo(4);
		assertThat(detail.categories())
				.containsExactly(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.TECHNICAL_DIGITAL);
		assertThat(detail.helpWith()).isEqualTo("CV ve staj");
		assertThat(detail.recommendationReasons()).hasSize(1);
		assertThat(detail.badges()).extracting(PublicBadgeResponse::code).containsExactly(BadgeCode.SESSIONS_10);
		assertThat(detail.subTopics()).containsExactly("cv", "interview");
		assertThat(detail.strongCourses()).containsExactly("CENG 315");
		assertThat(detail.projects()).containsExactly("Kampüs uygulaması");
		assertThat(detail.erasmus()).isEqualTo("TU Delft");
		assertThat(detail.linkedinUrl()).isEqualTo("https://linkedin.com/in/zeynep");
		assertThat(detail.weeklyHours()).containsExactly(tuesday);
		assertThat(detail.availableThisWeek()).isFalse();
	}

	@Test
	void aLinkedInAddressThatIsNotAWebLinkIsHeldBack() {
		peerMentor.setLinkedinUrl("javascript:alert(1)");

		assertThat(service.mentor(student.getId(), peerMentor.getId()).linkedinUrl()).isNull();
	}

	@Test
	void aProfileWithoutStudiesOrSubTopicsComesBackEmptyNotNull() {
		programProfiles = List.of(profile(peerMentor, List.of("CAREER_INTERNSHIP"), "CV ve staj", null, 0));

		PeerMentorDetailResponse detail = service.mentor(student.getId(), peerMentor.getId());

		assertThat(detail.subTopics()).isEmpty();
		assertThat(detail.strongCourses()).isEmpty();
		assertThat(detail.projects()).isEmpty();
		assertThat(detail.erasmus()).isNull();
		assertThat(detail.linkedinUrl()).isNull();
	}

	@Test
	void aProfileIsFoundOnlyForAPeerMentorTheDirectoryLists() {
		assertThatThrownBy(() -> service.mentor(student.getId(), teknokentMentor.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.mentor(student.getId(), UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.mentor(student.getId(), student.getId()))
				.isInstanceOf(ResourceNotFoundException.class);

		activate(EnumSet.of(PeerTopicCategory.INTERNATIONAL));
		assertThatThrownBy(() -> service.mentor(student.getId(), peerMentor.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(profiles, never()).findAvailabilityRules(any());
	}

	@Test
	void aTeknokentMenteeCannotOpenAPeerProfileOrTheCounts() {
		AppUser founder = user("Startup Founder", Set.of(), UserRole.MENTEE);

		assertThatThrownBy(() -> service.mentor(founder.getId(), peerMentor.getId()))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> service.mentorCounts(founder.getId()))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void countsListEveryActiveCategoryWithHowManyOfTheProgramsPeerMentorsOfferIt() {
		activate(EnumSet.of(PeerTopicCategory.INTERNATIONAL, PeerTopicCategory.TECHNICAL_DIGITAL,
				PeerTopicCategory.CAREER_INTERNSHIP));

		assertThat(service.mentorCounts(student.getId())).containsExactly(
				new PeerCategoryCountResponse(PeerTopicCategory.CAREER_INTERNSHIP, 1),
				new PeerCategoryCountResponse(PeerTopicCategory.TECHNICAL_DIGITAL, 1),
				new PeerCategoryCountResponse(PeerTopicCategory.INTERNATIONAL, 0));
	}

	@Test
	void theDirectoryFiltersBySubTopicIgnoringCaseAndSpaces() {
		assertThat(service.mentors(student.getId(), null, null, "CV", false)).hasSize(1);
		assertThat(service.mentors(student.getId(), null, null, "  interview ", false)).hasSize(1);
		assertThat(service.mentors(student.getId(), null, null, "erasmus", false)).isEmpty();
		assertThat(service.mentors(student.getId(), null, null, " ", false)).hasSize(1);
	}

	@Test
	void aMentorWithAFreeSlotThisWeekIsMarkedAndTheFilterKeepsOnlyThem() {
		when(slots.findMentorIdsWithFreeSlotBetween(any(), any(), any(), eq(MeetingStatus.SCHEDULED)))
				.thenReturn(List.of(peerMentor.getId()));

		assertThat(service.mentors(student.getId(), null, null, null, true)).singleElement()
				.satisfies(card -> assertThat(card.availableThisWeek()).isTrue());
		assertThat(service.mentor(student.getId(), peerMentor.getId()).availableThisWeek()).isTrue();

		when(slots.findMentorIdsWithFreeSlotBetween(any(), any(), any(), eq(MeetingStatus.SCHEDULED)))
				.thenReturn(List.of());
		assertThat(service.mentors(student.getId(), null, null, null, true)).isEmpty();
		assertThat(service.mentors(student.getId(), null, null, null, false)).singleElement()
				.satisfies(card -> assertThat(card.availableThisWeek()).isFalse());
	}

	@Test
	@SuppressWarnings("unchecked")
	void thisWeekRunsFromTheBookingLeadToSevenDaysAheadForTheListedMentorsOnly() {
		Instant before = Instant.now();

		service.mentors(student.getId(), null, null, null, true);

		ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
		ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
		verify(slots).findMentorIdsWithFreeSlotBetween(ids.capture(), from.capture(), until.capture(),
				eq(MeetingStatus.SCHEDULED));
		assertThat(ids.getValue()).containsExactly(peerMentor.getId());
		assertThat(from.getValue()).isAfterOrEqualTo(before.plus(MeetingSlotTimes.MIN_BOOKING_LEAD));
		assertThat(Duration.between(from.getValue(), until.getValue()))
				.isEqualTo(Duration.ofDays(7).minus(MeetingSlotTimes.MIN_BOOKING_LEAD));
	}

	@Test
	void anEmptyDirectoryAsksNothingAboutSlots() {
		when(programs.programOf(student)).thenReturn(Optional.empty());

		assertThat(service.mentors(student.getId(), null, null, null, true)).isEmpty();
		verify(slots, never()).findMentorIdsWithFreeSlotBetween(any(), any(), any(), any());
	}

	@Test
	void theWizardSavesALevelForChosenCategoriesOnlyAndTheExperienceCardsInOrder() {
		when(profileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		Map<PeerTopicCategory, PeerTopicLevel> levels = new EnumMap<>(PeerTopicCategory.class);
		levels.put(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED);
		levels.put(PeerTopicCategory.ENTREPRENEURSHIP, PeerTopicLevel.BASIC);

		PeerMentorProfileResponse saved = service.saveProfile(peerMentor.getId(), wizard(levels, List.of(
				new PeerExperienceCardRequest(PeerExperienceKind.INTERNSHIP, "  Yazılım stajyeri ", " Aselsan ",
						YearMonth.of(2025, 6), YearMonth.of(2025, 8), "  "),
				new PeerExperienceCardRequest(PeerExperienceKind.CLUB, "Kulüp başkanı", null, YearMonth.of(2024, 10),
						null, "IEEE ODTÜ"))));

		assertThat(published.getCategoryLevels()).containsExactly(
				Map.entry(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED));
		assertThat(published.getExperiences()).satisfiesExactly(
				internship -> {
					assertThat(internship.getKind()).isEqualTo(PeerExperienceKind.INTERNSHIP);
					assertThat(internship.getTitle()).isEqualTo("Yazılım stajyeri");
					assertThat(internship.getOrganization()).isEqualTo("Aselsan");
					assertThat(internship.getStartedOn()).isEqualTo(LocalDate.of(2025, 6, 1));
					assertThat(internship.getEndedOn()).isEqualTo(LocalDate.of(2025, 8, 1));
					assertThat(internship.getSummary()).isNull();
					assertThat(internship.getPeriod()).isNull();
				},
				club -> assertThat(club.getEndedOn()).isNull());
		assertThat(saved.levels()).containsExactly(Map.entry(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED));
		assertThat(saved.experiences()).extracting(PeerExperienceCardResponse::startedOn)
				.containsExactly(YearMonth.of(2025, 6), YearMonth.of(2024, 10));
	}

	@Test
	void leftOutLevelsAndCardsStayButALevelGoesWithItsCategoryAndAnEmptyListClearsTheCards() {
		when(profileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		published.getCategoryLevels().put(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.INTERMEDIATE);
		published.getCategoryLevels().put(PeerTopicCategory.TECHNICAL_DIGITAL, PeerTopicLevel.ADVANCED);
		published.getExperiences().add(card(PeerExperienceKind.JOB, "Asistan"));

		service.saveProfile(peerMentor.getId(), wizard(null, null));

		assertThat(published.getCategoryLevels()).containsExactly(
				Map.entry(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.INTERMEDIATE));
		assertThat(published.getExperiences()).extracting(MentorExperience::getTitle).containsExactly("Asistan");

		service.saveProfile(peerMentor.getId(), wizard(Map.of(), List.of()));

		assertThat(published.getCategoryLevels()).isEmpty();
		assertThat(published.getExperiences()).isEmpty();
	}

	@Test
	void aCardThatEndsBeforeItStartsIsRefusedBeforeAnythingChanges() {
		published.getExperiences().add(card(PeerExperienceKind.JOB, "Asistan"));

		assertThatThrownBy(() -> service.saveProfile(peerMentor.getId(), wizard(null, List.of(
				new PeerExperienceCardRequest(PeerExperienceKind.INTERNSHIP, "Staj", null, YearMonth.of(2025, 9),
						YearMonth.of(2025, 6), null)))))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(published.getExperiences()).extracting(MentorExperience::getTitle).containsExactly("Asistan");
		assertThat(published.getSpecialties()).isEmpty();
		verify(profileRepository, never()).save(any());
		verify(drafts, never()).deleteById(any());
	}

	@Test
	void theMentorsOwnProfileAndTheirPublicPageShowLevelsOnlyForCategoriesOnShowAndTheCards() {
		published.setSpecialties(new java.util.ArrayList<>(List.of("CAREER_INTERNSHIP", "INTERNATIONAL")));
		published.getCategoryLevels().put(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED);
		published.getCategoryLevels().put(PeerTopicCategory.INTERNATIONAL, PeerTopicLevel.BASIC);
		published.getExperiences().add(new MentorExperience("METU Stars Mentoru", null, null, null));
		when(peerCategories.activeIn(List.of(PROGRAM_ID))).thenReturn(EnumSet.of(PeerTopicCategory.CAREER_INTERNSHIP));
		activate(EnumSet.of(PeerTopicCategory.CAREER_INTERNSHIP));

		PeerMentorProfileResponse own = service.profile(peerMentor.getId());
		PeerMentorDetailResponse page = service.mentor(student.getId(), peerMentor.getId());

		assertThat(own.levels()).containsExactly(Map.entry(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED));
		assertThat(page.levels()).containsExactly(Map.entry(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicLevel.ADVANCED));
		assertThat(page.experiences()).singleElement().satisfies(line -> {
			assertThat(line.kind()).isEqualTo(PeerExperienceKind.OTHER);
			assertThat(line.title()).isEqualTo("METU Stars Mentoru");
			assertThat(line.startedOn()).isNull();
		});
	}

	private SavePeerMentorProfileRequest wizard(Map<PeerTopicCategory, PeerTopicLevel> levels,
			List<PeerExperienceCardRequest> experiences) {
		return new SavePeerMentorProfileRequest(null, List.of(PeerTopicCategory.CAREER_INTERNSHIP), List.of(),
				List.of(), List.of(), null, "Staj başvurusunda yardımcı olabilirim.", null, levels, experiences);
	}

	private static MentorExperience card(PeerExperienceKind kind, String title) {
		return new MentorExperience(title, null, null, null, kind, LocalDate.of(2024, 1, 1), null);
	}

	private AppUser user(String name, Set<IdentityKind> kinds, UserRole... roles) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setEmail(name.replace(' ', '.') + "@odtu-dev.invalid");
		user.setPrimaryRole(roles[0]);
		for (UserRole role : roles) {
			user.grant(role, PermissionSource.ADMIN, Instant.now());
		}
		when(lookup.requireUser(user.getId())).thenReturn(user);
		when(directory.kindsOf(user.getId())).thenReturn(List.copyOf(kinds));
		return user;
	}

	private static InstitutionPerson person(String department, int classYear) {
		InstitutionPerson person = new InstitutionPerson("odtu", UUID.randomUUID().toString());
		person.setDepartmentName(department);
		person.setClassYear(classYear);
		return person;
	}

	private MentorshipRequest request(UUID id, AppUser mentor, String supportArea) {
		MentorshipRequest request = new MentorshipRequest();
		request.setId(id);
		request.setRequester(student);
		request.setMentor(mentor);
		request.setSupportArea(supportArea);
		request.setSummary("mesaj");
		request.setStatus(MentorshipRequestStatus.PENDING);
		return request;
	}

	private void activate(Set<PeerTopicCategory> active) {
		when(peerCategories.activeIn(PROGRAM_ID)).thenReturn(active);
		when(peerCategories.activeIn(List.of(PROGRAM_ID))).thenReturn(active);
	}

	private static MentorProfileResponse profile(AppUser mentor, List<String> specialties, String about,
			Integer capacity, long active) {
		return profile(mentor, specialties, about, capacity, active, List.of(), null);
	}

	private static MentorProfileResponse profile(AppUser mentor, List<String> specialties, String about,
			Integer capacity, long active, List<String> offerings, StudentProfileResponse studies) {
		return new MentorProfileResponse(mentor.getId(), mentor.getFullName(), mentor.getEmail(), "ZD", "", "", "", "",
				"", "", "", "", "", "", "", specialties, List.of(), List.of(), capacity, about, 30, 0, null, null,
				List.of(), active, 0, List.of(), List.of(), List.of(), offerings, studies, List.of());
	}
}
