package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.CreatePeerMentoringRequest;
import com.dps.teknokent.dto.requests.SavePeerMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.PeerMentorCardResponse;
import com.dps.teknokent.dto.responses.PeerMentorProfileResponse;
import com.dps.teknokent.dto.responses.PeerMentoringRequestResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Peer mentoring for ODTÜ students: the peer mentors of the student's own program (never Teknokent mentors), and
 * requests to them. A request goes through the ordinary mentorship-request flow, filed under the student's team; a
 * student without one gets their hidden personal team at that moment.
 */
@Service
@RequiredArgsConstructor
public class PeerMentoringService {

	private static final Set<MentorshipRequestStatus> OPEN = EnumSet.of(MentorshipRequestStatus.PENDING,
			MentorshipRequestStatus.MORE_INFO_REQUESTED, MentorshipRequestStatus.COUNTER_PROPOSED);
	private static final Locale TR = Locale.forLanguageTag("tr");

	private final DomainLookupService domainLookupService;
	private final InstitutionDirectoryService institutionDirectoryService;
	private final InstitutionPersonRepository institutionPersonRepository;
	private final MenteeProgramService menteeProgramService;
	private final MentorProfileService mentorProfileService;
	private final MentorshipRequestService mentorshipRequestService;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final PersonalTeamService personalTeamService;
	private final MentorProfileRepository mentorProfileRepository;
	private final AppUserRepository appUserRepository;
	private final BadgeService badgeService;
	private final ProgramPeerCategoryService programPeerCategoryService;
	private final ProgramMentorRepository programMentorRepository;

	/** The peer mentors of the student's program, the student left out; empty for a student in no program. */
	@Transactional(readOnly = true)
	public List<PeerMentorCardResponse> mentors(UUID actorId, PeerTopicCategory category, String query) {
		AppUser student = requireStudent(actorId);
		Set<PeerTopicCategory> active = activeForStudent(student);
		if (category != null && !active.contains(category)) {
			return List.of();
		}
		String department = departmentOf(student.getId(), null);
		String needle = query == null || query.isBlank() ? null : query.trim().toLowerCase(TR);
		return peerMentorsFor(student).stream()
				.map(profile -> toCard(profile, department, active))
				.filter(card -> !card.categories().isEmpty())
				.filter(card -> category == null || card.categories().contains(category))
				.filter(card -> needle == null || matches(card, needle))
				.toList();
	}

	/**
	 * The categories the caller may use: as {@code MENTEE}, the ones active in the student's program; as
	 * {@code MENTOR}, the ones active in any program the student mentors in. Empty outside every program.
	 */
	@Transactional(readOnly = true)
	public List<PeerTopicCategory> categories(UUID actorId, UserRole side) {
		Set<PeerTopicCategory> active = side == UserRole.MENTOR
				? activeForMentor(requirePeerMentor(actorId))
				: activeForStudent(requireStudent(actorId));
		return active.stream().sorted().toList();
	}

	/** The student's own requests ({@code MENTEE}), or the ones a peer mentor received ({@code MENTOR}). */
	@Transactional(readOnly = true)
	public List<PeerMentoringRequestResponse> requests(UUID actorId, UserRole side) {
		List<MentorshipRequest> requests;
		if (side == UserRole.MENTOR) {
			AppUser mentor = requirePeerMentor(actorId);
			requests = mentorshipRequestRepository.findByMentorIdOrderByCreatedAtDesc(mentor.getId());
		} else {
			AppUser student = requireStudent(actorId);
			requests = mentorshipRequestRepository.findByRequesterIdOrderByCreatedAtDesc(student.getId());
		}
		return requests.stream()
				.filter(request -> PeerTopicCategory.fromText(request.getSupportArea()) != null)
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public PeerMentoringRequestResponse create(UUID actorId, CreatePeerMentoringRequest request) {
		AppUser student = requireStudent(actorId);
		if (!activeForStudent(student).contains(request.category())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerCategoryInactive"));
		}
		if (student.getId().equals(request.mentorId())) {
			throw new InvalidRequestException(Texts.forRequest("error.selfMentorshipRequest"));
		}
		boolean peerOfProgram = peerMentorsFor(student).stream()
				.anyMatch(profile -> profile.id().equals(request.mentorId()));
		if (!peerOfProgram) {
			throw new InvalidRequestException(Texts.forRequest("error.peerMentorOutsideProgram"));
		}
		boolean alreadyOpen = mentorshipRequestRepository.findByRequesterIdOrderByCreatedAtDesc(student.getId())
				.stream()
				.anyMatch(existing -> existing.getMentor().getId().equals(request.mentorId())
						&& OPEN.contains(existing.getStatus()));
		if (alreadyOpen) {
			throw new ConflictException(Texts.forRequest("error.peerRequestOpen"));
		}
		Organization team = personalTeamService.ensureFor(student);
		MentorshipRequestResponse created = mentorshipRequestService.create(new CreateMentorshipRequestSubmission(
				student.getId(),
				team.getId(),
				request.mentorId(),
				student.getFullName(),
				request.category().name(),
				request.message().trim(),
				MentorshipRequestUrgency.NOT_URGENT,
				MentorshipRequestFormat.SINGLE_MEETING,
				MeetingMode.NO_PREFERENCE,
				null,
				null,
				null));
		return toResponse(mentorshipRequestRepository.findById(created.id())
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship request not found: " + created.id())));
	}

	/** The peer mentor's own profile; 404 until they publish one with the wizard. */
	@Transactional(readOnly = true)
	public PeerMentorProfileResponse profile(UUID actorId) {
		AppUser mentor = requirePeerMentor(actorId);
		MentorProfile profile = mentorProfileRepository.findById(mentor.getId())
				.orElseThrow(() -> new ResourceNotFoundException("No peer-mentor profile yet: " + mentor.getId()));
		return toProfileResponse(mentor, profile);
	}

	/**
	 * Publishes or updates the wizard's answers. No approval step (board: "ek onay adımı yok"): the badge already made
	 * the student one of their program's mentors, so the profile shows in the program's directory at once.
	 */
	@Transactional
	public PeerMentorProfileResponse saveProfile(UUID actorId, SavePeerMentorProfileRequest request) {
		AppUser mentor = requirePeerMentor(actorId);
		if (!activeForMentor(mentor).containsAll(request.categories())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerCategoryInactive"));
		}
		MentorProfile profile = mentorProfileRepository.findById(mentor.getId())
				.orElseGet(() -> MentorProfile.blankFor(mentor, mentorProfileRepository.findMaxSortOrder() + 1));
		Optional<InstitutionPerson> person = institutionPersonRepository.findFirstByUserIdOrderBySyncedAtDesc(
				mentor.getId());
		profile.setSpecialties(new ArrayList<>(request.categories().stream().distinct().map(Enum::name).toList()));
		profile.setOfferings(new ArrayList<>(cleaned(request.subTopics())));
		profile.setStrongCourses(new ArrayList<>(cleaned(request.strongCourses())));
		profile.setProjects(new ArrayList<>(cleaned(request.projects())));
		profile.setErasmus(blankToNull(request.erasmus()));
		profile.setAbout(request.helpWith().trim());
		person.map(InstitutionPerson::getDepartmentName).ifPresent(profile::setDepartment);
		person.map(InstitutionPerson::getClassYear).map(String::valueOf).ifPresent(profile::setStudyYear);
		mentor.setLinkedinUrl(blankToNull(request.linkedinUrl()));
		appUserRepository.save(mentor);
		return toProfileResponse(mentor, mentorProfileRepository.save(profile));
	}

	private PeerMentorProfileResponse toProfileResponse(AppUser mentor, MentorProfile profile) {
		Optional<InstitutionPerson> person = institutionPersonRepository.findFirstByUserIdOrderBySyncedAtDesc(
				mentor.getId());
		return new PeerMentorProfileResponse(
				profile.getSpecialties().stream().map(PeerTopicCategory::fromText).filter(Objects::nonNull).distinct()
						.toList(),
				List.copyOf(profile.getOfferings()),
				List.copyOf(profile.getStrongCourses()),
				List.copyOf(profile.getProjects()),
				profile.getErasmus(),
				profile.getAbout(),
				mentor.getLinkedinUrl(),
				person.map(InstitutionPerson::getDepartmentName).orElse(profile.getDepartment()),
				person.map(InstitutionPerson::getClassYear).orElseGet(() -> parseYear(profile.getStudyYear())),
				profile.getMenteeCapacity());
	}

	private static List<String> cleaned(List<String> values) {
		return values == null ? List.of() : values.stream().map(String::trim).filter(value -> !value.isEmpty())
				.distinct().toList();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private List<MentorProfileResponse> peerMentorsFor(AppUser student) {
		Optional<UUID> program = menteeProgramService.programOf(student);
		if (program.isEmpty()) {
			return List.of();
		}
		return mentorProfileService
				.findAmongExcept(menteeProgramService.acceptedMentorIds(program.get()), student.getId()).stream()
				.filter(profile -> isPeerMentor(domainLookupService.requireUser(profile.id())))
				.toList();
	}

	/** Package-private for the student workspace's other services, so they share one notion of an ODTÜ student. */
	AppUser requireStudent(UUID actorId) {
		AppUser user = domainLookupService.requireUser(actorId);
		if (!user.has(UserRole.MENTEE) || !isStudent(user)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.peerMentoringStudentsOnly"));
		}
		return user;
	}

	/**
	 * The mentor side (received requests, profile) is open to a student who holds MENTOR now. D4 seam: a revoked
	 * Akran Mentoru badge takes MENTOR away (BadgeService), so a student whose badge was revoked can no longer act on
	 * requests or mentorships they already have; what should happen to those is the parked decision D4, and this is
	 * the one check to change when it is made.
	 */
	private AppUser requirePeerMentor(UUID actorId) {
		AppUser user = domainLookupService.requireUser(actorId);
		if (!isPeerMentor(user)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.peerMentoringStudentsOnly"));
		}
		return user;
	}

	private boolean isPeerMentor(AppUser user) {
		return user.has(UserRole.MENTOR) && isStudent(user);
	}

	private boolean isStudent(AppUser user) {
		return institutionDirectoryService.kindsOf(user.getId()).contains(IdentityKind.STUDENT);
	}

	private Set<PeerTopicCategory> activeForStudent(AppUser student) {
		return menteeProgramService.programOf(student)
				.map(programPeerCategoryService::activeIn)
				.orElseGet(() -> EnumSet.noneOf(PeerTopicCategory.class));
	}

	private Set<PeerTopicCategory> activeForMentor(AppUser mentor) {
		return programPeerCategoryService.activeIn(programMentorRepository
				.findByMentorIdAndStatusOrderByCreatedAtDesc(mentor.getId(), ProgramMentorStatus.ACCEPTED).stream()
				.map(ProgramMentor::getProgramId)
				.toList());
	}

	/** Only the categories active in the student's program show on the card. */
	private PeerMentorCardResponse toCard(MentorProfileResponse profile, String studentDepartment,
			Set<PeerTopicCategory> active) {
		Optional<InstitutionPerson> person = institutionPersonRepository.findFirstByUserIdOrderBySyncedAtDesc(profile.id());
		String fallbackDepartment = profile.studentProfile() == null ? null : profile.studentProfile().department();
		String department = person.map(InstitutionPerson::getDepartmentName).orElse(fallbackDepartment);
		Integer classYear = person.map(InstitutionPerson::getClassYear)
				.orElseGet(() -> parseYear(profile.studentProfile() == null ? null : profile.studentProfile().studyYear()));
		List<PeerTopicCategory> categories = (profile.specialties() == null ? List.<String>of() : profile.specialties())
				.stream()
				.map(PeerTopicCategory::fromText)
				.filter(Objects::nonNull)
				.filter(active::contains)
				.distinct()
				.toList();
		String helpWith = profile.about() != null && !profile.about().isBlank()
				? profile.about()
				: String.join(", ", profile.offerings() == null ? List.of() : profile.offerings());
		List<String> reasons = department != null && department.equalsIgnoreCase(studentDepartment)
				? List.of(Texts.forRequest("peer.reason.sameDepartment"))
				: List.of();
		boolean full = profile.menteeCapacity() != null && profile.activeMenteeCount() >= profile.menteeCapacity();
		return new PeerMentorCardResponse(profile.id(), profile.fullName(), department, classYear, categories, helpWith,
				reasons, full, badgeService.publicOf(profile.id()));
	}

	private PeerMentoringRequestResponse toResponse(MentorshipRequest request) {
		AppUser mentor = request.getMentor();
		AppUser mentee = request.getRequester();
		return new PeerMentoringRequestResponse(
				request.getId(),
				new PeerMentoringRequestResponse.Person(mentor.getId(), mentor.getFullName(),
						departmentOf(mentor.getId(), null)),
				new PeerMentoringRequestResponse.Person(mentee.getId(), mentee.getFullName(),
						departmentOf(mentee.getId(), null)),
				PeerTopicCategory.fromText(request.getSupportArea()),
				request.getSummary(),
				request.getStatus(),
				request.getCreatedAt());
	}

	String departmentOf(UUID userId, String fallback) {
		return institutionPersonRepository.findFirstByUserIdOrderBySyncedAtDesc(userId)
				.map(InstitutionPerson::getDepartmentName)
				.orElse(fallback);
	}

	private static boolean matches(PeerMentorCardResponse card, String needle) {
		return contains(card.fullName(), needle) || contains(card.department(), needle)
				|| contains(card.helpWith(), needle);
	}

	private static boolean contains(String text, String needle) {
		return text != null && text.toLowerCase(TR).contains(needle);
	}

	private static Integer parseYear(String text) {
		if (text == null) {
			return null;
		}
		String digits = text.replaceAll("\\D", "");
		return digits.isEmpty() ? null : Integer.valueOf(digits.substring(0, 1));
	}
}
