package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
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
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.CreatePeerMentoringRequest;
import com.dps.teknokent.dto.requests.SavePeerMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.PeerMentorCardResponse;
import com.dps.teknokent.dto.responses.PeerMentorProfileResponse;
import com.dps.teknokent.dto.responses.PeerMentoringRequestResponse;
import com.dps.teknokent.dto.responses.PublicBadgeResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
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
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;

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
	private PeerMentoringService service;

	private AppUser student;
	private AppUser peerMentor;
	private AppUser teknokentMentor;

	@BeforeEach
	void setUp() {
		service = new PeerMentoringService(lookup, directory, people, programs, profiles, requestService, requests,
				personalTeams, profileRepository, users, badges, peerCategories, programMentors, programRepository);
		student = user("Ayşe Yılmaz", Set.of(IdentityKind.STUDENT), UserRole.MENTEE);
		student.setProgramId(PROGRAM_ID);
		peerMentor = user("Zeynep Demir", Set.of(IdentityKind.STUDENT), UserRole.MENTEE, UserRole.MENTOR);
		teknokentMentor = user("QA Test Mentor", Set.of(), UserRole.MENTOR);
		when(programs.programOf(student)).thenReturn(Optional.of(PROGRAM_ID));
		Set<UUID> accepted = Set.of(peerMentor.getId(), teknokentMentor.getId());
		when(programs.acceptedMentorIds(PROGRAM_ID)).thenReturn(accepted);
		when(profiles.findAmongExcept(accepted, student.getId())).thenReturn(List.of(
				profile(peerMentor, List.of("Kariyer ve Staj", "TECHNICAL_DIGITAL", "Bambaşka"), "CV ve staj", 3, 1),
				profile(teknokentMentor, List.of("Fintech"), "Startup büyütme", null, 0)));
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
				"https://linkedin.com/in/zeynep"));

		ArgumentCaptor<MentorProfile> profile = ArgumentCaptor.forClass(MentorProfile.class);
		verify(profileRepository).save(profile.capture());
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
		List<PeerMentorCardResponse> cards = service.mentors(student.getId(), null, null);

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
		assertThat(service.mentors(student.getId(), PeerTopicCategory.INTERNATIONAL, null)).isEmpty();
		assertThat(service.mentors(student.getId(), PeerTopicCategory.CAREER_INTERNSHIP, null)).hasSize(1);
		assertThat(service.mentors(student.getId(), null, "zeynep")).hasSize(1);
		assertThat(service.mentors(student.getId(), null, "erasmus")).isEmpty();
	}

	@Test
	void onlyTheProgramsActiveCategoriesShowAndAMentorWithNoneOfThemIsLeftOut() {
		activate(EnumSet.of(PeerTopicCategory.TECHNICAL_DIGITAL));

		assertThat(service.mentors(student.getId(), null, null)).singleElement()
				.satisfies(card -> assertThat(card.categories()).containsExactly(PeerTopicCategory.TECHNICAL_DIGITAL));
		assertThat(service.mentors(student.getId(), PeerTopicCategory.CAREER_INTERNSHIP, null)).isEmpty();
		assertThat(service.categories(student.getId(), UserRole.MENTEE))
				.containsExactly(PeerTopicCategory.TECHNICAL_DIGITAL);

		activate(EnumSet.of(PeerTopicCategory.INTERNATIONAL));
		assertThat(service.mentors(student.getId(), null, null)).isEmpty();
	}

	@Test
	void noCategoryIsActiveUntilTheCoordinatorTurnsOneOn() {
		activate(EnumSet.noneOf(PeerTopicCategory.class));

		assertThat(service.categories(student.getId(), UserRole.MENTEE)).isEmpty();
		assertThat(service.mentors(student.getId(), null, null)).isEmpty();
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
				List.of(), null, "Staj başvurusunda yardımcı olabilirim.", null)))
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

		assertThat(service.mentors(student.getId(), null, null)).isEmpty();
	}

	@Test
	void aTeknokentMenteeCannotUsePeerMentoring() {
		AppUser founder = user("Kurucu", Set.of(), UserRole.MENTEE);

		assertThatThrownBy(() -> service.mentors(founder.getId(), null, null))
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
		return new MentorProfileResponse(mentor.getId(), mentor.getFullName(), mentor.getEmail(), "ZD", "", "", "", "",
				"", "", "", "", "", "", "", specialties, List.of(), List.of(), capacity, about, 30, 0, null, null,
				List.of(), active, 0, List.of(), List.of(), List.of(), List.of(), null, List.of());
	}
}
