package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.CreatePeerMentoringRequest;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.PeerMentorCardResponse;
import com.dps.teknokent.dto.responses.PeerMentoringRequestResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;

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
	private PeerMentoringService service;

	private AppUser student;
	private AppUser peerMentor;
	private AppUser teknokentMentor;

	@BeforeEach
	void setUp() {
		service = new PeerMentoringService(lookup, directory, people, programs, profiles, requestService, requests,
				personalTeams);
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

	private static MentorProfileResponse profile(AppUser mentor, List<String> specialties, String about,
			Integer capacity, long active) {
		return new MentorProfileResponse(mentor.getId(), mentor.getFullName(), mentor.getEmail(), "ZD", "", "", "", "",
				"", "", "", "", "", "", "", specialties, List.of(), List.of(), capacity, about, 30, 0, null, null,
				List.of(), active, 0, List.of(), List.of(), List.of(), List.of(), null, List.of());
	}
}
