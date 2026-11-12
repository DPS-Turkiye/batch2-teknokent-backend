package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.CreatePeerMentoringRequest;
import com.dps.teknokent.dto.requests.SavePeerMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.PeerCategoryCountResponse;
import com.dps.teknokent.dto.responses.PeerMentorCardResponse;
import com.dps.teknokent.dto.responses.PeerMentorDetailResponse;
import com.dps.teknokent.dto.responses.PeerMentorProfileResponse;
import com.dps.teknokent.dto.responses.PeerMentoringRequestResponse;
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
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
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
	/** "Bu hafta müsait": a free slot within the next seven days. */
	static final Duration THIS_WEEK = Duration.ofDays(7);
	private static final Pattern WEB_LINK = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);

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
	private final ProgramRepository programRepository;
	private final MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;

	/**
	 * The peer mentors of the student's program, the student left out; empty for a student in no program. A
	 * sub-topic keeps the mentors who offer it; {@code availableThisWeek} keeps those with a free slot in {@link
	 * #THIS_WEEK}.
	 */
	@Transactional(readOnly = true)
	public List<PeerMentorCardResponse> mentors(UUID actorId, PeerTopicCategory category, String query,
			String subTopic, boolean availableThisWeek) {
		AppUser student = requireStudent(actorId);
		Set<PeerTopicCategory> active = activeForStudent(student);
		if (category != null && !active.contains(category)) {
			return List.of();
		}
		String department = departmentOf(student.getId(), null);
		String needle = query == null || query.isBlank() ? null : query.trim().toLowerCase(TR);
		String wantedSubTopic = blankToNull(subTopic);
		List<MentorProfileResponse> mentors = peerMentorsFor(student, null).stream()
				.filter(profile -> wantedSubTopic == null || offers(profile, wantedSubTopic))
				.toList();
		Set<UUID> free = freeThisWeek(mentors);
		return mentors.stream()
				.map(profile -> toCard(profile, department, active, free.contains(profile.id())))
				.filter(card -> !card.categories().isEmpty())
				.filter(card -> category == null || card.categories().contains(category))
				.filter(card -> !availableThisWeek || card.availableThisWeek())
				.filter(card -> needle == null || matches(card, needle))
				.toList();
	}

	/**
	 * One peer mentor's profile page, for a student of the same program: the same mentors the directory lists, so a
	 * mentor outside the program, the student themselves, or one with no active category is not found.
	 */
	@Transactional(readOnly = true)
	public PeerMentorDetailResponse mentor(UUID actorId, UUID mentorId) {
		AppUser student = requireStudent(actorId);
		Set<PeerTopicCategory> active = activeForStudent(student);
		MentorProfileResponse profile = peerMentorsFor(student, mentorId).stream()
				.findFirst()
				.orElseThrow(() -> mentorNotFound(mentorId));
		PeerMentorCardResponse card = toCard(profile, departmentOf(student.getId(), null), active,
				!freeThisWeek(List.of(profile)).isEmpty());
		if (card.categories().isEmpty()) {
			throw mentorNotFound(mentorId);
		}
		return PeerMentorDetailResponse.of(card, profile,
				webLinkOrNull(domainLookupService.requireUser(mentorId).getLinkedinUrl()),
				mentorProfileService.findAvailabilityRules(mentorId));
	}

	/** For each category active in the student's program, how many of its peer mentors offer it. */
	@Transactional(readOnly = true)
	public List<PeerCategoryCountResponse> mentorCounts(UUID actorId) {
		AppUser student = requireStudent(actorId);
		Set<PeerTopicCategory> active = activeForStudent(student);
		Map<PeerTopicCategory, Long> counts = peerMentorsFor(student, null).stream()
				.flatMap(profile -> offeredCategories(profile.specialties(), active).stream())
				.collect(Collectors.groupingBy(Function.identity(), () -> new EnumMap<>(PeerTopicCategory.class),
						Collectors.counting()));
		return active.stream()
				.sorted()
				.map(category -> new PeerCategoryCountResponse(category,
						counts.getOrDefault(category, 0L).intValue()))
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
		MentorProfileResponse mentorProfile = peerMentorsFor(student, request.mentorId()).stream()
				.findFirst()
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.peerMentorOutsideProgram")));
		if (!offeredCategories(mentorProfile.specialties(), activeForStudent(student)).contains(request.category())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerMentorCategoryNotOffered"));
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
				offeredCategories(profile.getSpecialties(), activeForMentor(mentor)),
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

	/**
	 * The peer mentors of the student's program, the student left out; with {@code onlyMentorId}, at most that one, so a
	 * single profile counts mentees and meetings for that mentor alone.
	 */
	private List<MentorProfileResponse> peerMentorsFor(AppUser student, UUID onlyMentorId) {
		Optional<UUID> program = menteeProgramService.programOf(student);
		if (program.isEmpty()) {
			return List.of();
		}
		Set<UUID> accepted = menteeProgramService.acceptedMentorIds(program.get());
		Set<UUID> wanted = onlyMentorId == null ? accepted
				: accepted.contains(onlyMentorId) ? Set.of(onlyMentorId) : Set.of();
		if (wanted.isEmpty()) {
			return List.of();
		}
		return mentorProfileService.findAmongExcept(wanted, student.getId()).stream()
				.filter(profile -> isPeerMentor(domainLookupService.requireUser(profile.id())))
				.toList();
	}

	/** Compared the way the text search is, with Turkish case rules ("İ" and "ı"). */
	private static boolean offers(MentorProfileResponse profile, String subTopic) {
		String wanted = subTopic.toLowerCase(TR);
		return profile.offerings() != null
				&& profile.offerings().stream().anyMatch(offered -> offered.trim().toLowerCase(TR).equals(wanted));
	}

	/**
	 * The mentors among these with a free slot in the next {@link #THIS_WEEK} that can still be booked, i.e. no sooner
	 * than the booking lead; one query for the whole list.
	 */
	private Set<UUID> freeThisWeek(Collection<MentorProfileResponse> mentors) {
		if (mentors.isEmpty()) {
			return Set.of();
		}
		Instant now = Instant.now();
		return Set.copyOf(mentorAvailabilitySlotRepository.findMentorIdsWithFreeSlotBetween(
				mentors.stream().map(MentorProfileResponse::id).toList(), now.plus(MeetingSlotTimes.MIN_BOOKING_LEAD),
				now.plus(THIS_WEEK), MeetingStatus.SCHEDULED));
	}

	/**
	 * Only the peer wizard checks that the LinkedIn address is a web link; sign-up and the profile form do not, so
	 * anything else is held back rather than handed to another student's browser.
	 */
	private static String webLinkOrNull(String url) {
		return url != null && WEB_LINK.matcher(url.trim()).matches() ? url.trim() : null;
	}

	private static ResourceNotFoundException mentorNotFound(UUID mentorId) {
		return new ResourceNotFoundException("Peer mentor not found: " + mentorId);
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

	/** Over the running programs the student mentors in; an archived program's categories no longer count. */
	private Set<PeerTopicCategory> activeForMentor(AppUser mentor) {
		List<UUID> programIds = programMentorRepository
				.findByMentorIdAndStatusOrderByCreatedAtDesc(mentor.getId(), ProgramMentorStatus.ACCEPTED).stream()
				.map(ProgramMentor::getProgramId)
				.toList();
		return programPeerCategoryService.activeIn(programRepository.findAllById(programIds).stream()
				.filter(program -> program.getStatus() == ProgramStatus.ACTIVE)
				.map(Program::getId)
				.toList());
	}

	/**
	 * The categories a mentor's stored topics name that are active now. A category the coordinator turned off
	 * drops out here, so the wizard does not offer it back and the directory does not show it.
	 */
	private static List<PeerTopicCategory> offeredCategories(List<String> specialties, Set<PeerTopicCategory> active) {
		return (specialties == null ? List.<String>of() : specialties).stream()
				.map(PeerTopicCategory::fromText)
				.filter(Objects::nonNull)
				.filter(active::contains)
				.distinct()
				.toList();
	}

	/** Only the categories active in the student's program show on the card. */
	private PeerMentorCardResponse toCard(MentorProfileResponse profile, String studentDepartment,
			Set<PeerTopicCategory> active, boolean availableThisWeek) {
		Optional<InstitutionPerson> person = institutionPersonRepository.findFirstByUserIdOrderBySyncedAtDesc(profile.id());
		String fallbackDepartment = profile.studentProfile() == null ? null : profile.studentProfile().department();
		String department = person.map(InstitutionPerson::getDepartmentName).orElse(fallbackDepartment);
		Integer classYear = person.map(InstitutionPerson::getClassYear)
				.orElseGet(() -> parseYear(profile.studentProfile() == null ? null : profile.studentProfile().studyYear()));
		List<PeerTopicCategory> categories = offeredCategories(profile.specialties(), active);
		String helpWith = profile.about() != null && !profile.about().isBlank()
				? profile.about()
				: String.join(", ", profile.offerings() == null ? List.of() : profile.offerings());
		List<String> reasons = department != null && department.equalsIgnoreCase(studentDepartment)
				? List.of(Texts.forRequest("peer.reason.sameDepartment"))
				: List.of();
		boolean full = profile.menteeCapacity() != null && profile.activeMenteeCount() >= profile.menteeCapacity();
		return new PeerMentorCardResponse(profile.id(), profile.fullName(), department, classYear, categories, helpWith,
				reasons, full, badgeService.publicOf(profile.id()), availableThisWeek);
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
