package com.dps.teknokent.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.domain.MatchNeedSource;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.ProgramMatchingResponse;
import com.dps.teknokent.entity.AppUser;
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

import lombok.RequiredArgsConstructor;

/**
 * Flow 03.2: a coordinator matches a program's waiting teams with mentors from its pool. A match starts the
 * mentorship at once, tells both sides, and closes whatever requests the team still had open.
 */
@Service
@RequiredArgsConstructor
public class ProgramMatchingService {

	private static final Set<MentorshipStatus> MATCHED = EnumSet.of(MentorshipStatus.ACTIVE, MentorshipStatus.PLANNED);
	private static final Set<MentorshipStatus> ENDED = EnumSet.of(MentorshipStatus.COMPLETED, MentorshipStatus.CANCELLED);
	private static final Set<MentorshipRequestStatus> OPEN_REQUEST = EnumSet.of(
			MentorshipRequestStatus.PENDING,
			MentorshipRequestStatus.MORE_INFO_REQUESTED,
			MentorshipRequestStatus.COUNTER_PROPOSED);
	private static final String GENERAL_TOPIC = "Genel mentorluk";

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final OrganizationRepository organizationRepository;
	private final MentorshipRepository mentorshipRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final MentorProfileRepository mentorProfileRepository;
	private final AppUserRepository appUserRepository;
	private final MentorRecommendationService recommendationService;
	private final NotificationService notificationService;
	private final PlatformTransactionManager transactionManager;

	@Transactional(readOnly = true)
	public ProgramMatchingResponse.Overview overview(UUID actorId, UUID programId) {
		return overviewOf(coordinatorProgramService.requireManagedProgram(actorId, programId));
	}

	/**
	 * Not one transaction: the recommendation engine caches mentor embeddings as it ranks, and those writes must
	 * commit on their own, as they do on the mentee side. Only the reads before it share a read-only transaction.
	 */
	public ProgramMatchingResponse.Suggestions suggestions(UUID actorId, UUID programId, UUID organizationId) {
		SuggestionInput input = readOnly().execute(status -> suggestionInput(actorId, programId, organizationId));
		if (input.pool().isEmpty()) {
			return new ProgramMatchingResponse.Suggestions(null, List.of(), List.of());
		}
		var ranked = recommendationService.recommendAmong(input.request(), input.pool());
		Map<UUID, Long> load = mentorshipRepository.countOrganizationsByMentor(input.pool(), MATCHED).stream()
				.collect(Collectors.toMap(MentorCount::getMentorId, MentorCount::getTotal));
		Map<UUID, Integer> capacity = mentorProfileRepository.findAllById(input.pool()).stream()
				.filter(profile -> profile.getMenteeCapacity() != null)
				.collect(Collectors.toMap(profile -> profile.getId(), profile -> profile.getMenteeCapacity()));
		Function<MentorProfileResponse, ProgramMatchingResponse.Candidate> candidate = mentor -> new ProgramMatchingResponse.Candidate(
				mentor.id(),
				mentor.fullName(),
				mentor.professionalTitle(),
				mentor.specialties(),
				mentor.availabilityLabel() == null || mentor.availabilityLabel().isBlank() ? null : mentor.availabilityLabel(),
				load.getOrDefault(mentor.id(), 0L).intValue(),
				capacity.getOrDefault(mentor.id(), input.defaultCapacity()),
				mentor.recommendationReasons() == null ? List.of() : mentor.recommendationReasons());
		List<ProgramMatchingResponse.Candidate> recommended = ranked.mentors().stream().map(candidate).toList();
		List<ProgramMatchingResponse.Candidate> everyone = recommendationService.poolProfiles(input.pool()).stream()
				.map(mentor -> candidate.apply(mentor.withRecommendation(List.of(), List.of())))
				.sorted(Comparator.comparing(ProgramMatchingResponse.Candidate::fullName))
				.toList();
		return new ProgramMatchingResponse.Suggestions(ranked.matchingMethod(), recommended, everyone);
	}

	private SuggestionInput suggestionInput(UUID actorId, UUID programId, UUID organizationId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		Organization team = requireTeam(program, organizationId);
		Set<UUID> matchedMentors = mentorshipRepository.findByOrganizationIdIn(List.of(team.getId())).stream()
				.filter(mentorship -> MATCHED.contains(mentorship.getStatus()))
				.map(mentorship -> mentorship.getMentor().getId())
				.collect(Collectors.toSet());
		List<UUID> pool = poolOf(program).stream().filter(id -> !matchedMentors.contains(id)).toList();
		Need need = needOf(team);
		RecommendMentorsRequest request = new RecommendMentorsRequest(
				need.topic() == null ? GENERAL_TOPIC : need.topic(),
				firstPresent(need.text(), need.topic(), team.getDescription(), team.getName()),
				null,
				null,
				null,
				team.getSector() == null ? null : team.getSector().value(),
				team.getMaturity() == null ? null : team.getMaturity().value(),
				team.getDescription(),
				List.of(),
				null);
		return new SuggestionInput(pool, request, program.getDefaultMentorCapacity());
	}

	private TransactionTemplate readOnly() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setReadOnly(true);
		return template;
	}

	/** "Eşleşmeyi onayla": the mentorship starts today; a full mentor is allowed, the screen warned about it. */
	@Transactional
	public ProgramMatchingResponse.Overview match(UUID actorId, UUID programId, UUID organizationId, UUID mentorId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		Organization team = requireTeam(program, organizationId);
		if (!poolOf(program).contains(mentorId)) {
			throw new InvalidRequestException("Only a mentor in this program's pool can be matched.");
		}
		AppUser mentor = appUserRepository.findById(mentorId)
				.filter(user -> user.has(UserRole.MENTOR))
				.orElseThrow(() -> new ResourceNotFoundException("Mentor not found: " + mentorId));
		boolean alreadyMatched = mentorshipRepository.findByOrganizationIdIn(List.of(team.getId())).stream()
				.anyMatch(mentorship -> MATCHED.contains(mentorship.getStatus())
						&& mentorship.getMentor().getId().equals(mentorId));
		if (alreadyMatched) {
			throw new ConflictException("This mentor is already working with this team.");
		}
		Need need = needOf(team);
		Mentorship matched = Mentorship.fromRequest(
				new CreateMentorshipRequest(
						mentorId,
						team.getId(),
						MentorshipStatus.ACTIVE,
						LocalDate.now(MeetingSlotTimes.APP_ZONE),
						null,
						1,
						need.topic()),
				mentor,
				team);
		matched.setProgram(program);
		mentorshipRepository.save(matched);
		closeOpenRequests(team, mentorId);
		notificationService.create(
				mentor,
				Texts.get(mentor, "notification.matchedWithTeam.title"),
				Texts.get(mentor, "notification.matchedWithTeam.body", program.getName(), team.getName()),
				"/mentor-dashboard");
		appUserRepository.findByOrganizationIdOrderByFullNameAsc(team.getId()).stream()
				.filter(member -> member.has(UserRole.MENTEE))
				.forEach(member -> notificationService.create(
						member,
						Texts.get(member, "notification.mentorAssigned.title"),
						Texts.get(member, "notification.mentorAssigned.body", program.getName(), mentor.getFullName()),
						"/mentorships"));
		return overviewOf(program);
	}

	private ProgramMatchingResponse.Overview overviewOf(Program program) {
		List<ProgramStartup> startups = programStartupRepository.findByProgramIdIn(List.of(program.getId()));
		List<UUID> teamIds = startups.stream().map(ProgramStartup::getOrganizationId).toList();
		Map<UUID, Organization> teams = organizationRepository.findAllById(teamIds).stream()
				.filter(Organization::isApprovedForProgram)
				.collect(Collectors.toMap(Organization::getId, Function.identity()));
		List<Mentorship> mentorships = teamIds.isEmpty()
				? List.of()
				: mentorshipRepository.findByOrganizationIdIn(teamIds).stream()
						.filter(mentorship -> CoordinatorProgramService.countsIn(mentorship, Set.of(program.getId())))
						.toList();
		Set<UUID> matchedTeams = mentorships.stream()
				.filter(mentorship -> MATCHED.contains(mentorship.getStatus()))
				.map(mentorship -> mentorship.getOrganization().getId())
				.collect(Collectors.toSet());
		LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		List<ProgramMatchingResponse.WaitingTeam> waiting = startups.stream()
				.filter(startup -> !matchedTeams.contains(startup.getOrganizationId()))
				.map(startup -> Optional.ofNullable(teams.get(startup.getOrganizationId()))
						.map(team -> waitingTeam(team, startup, today)))
				.flatMap(Optional::stream)
				.sorted(Comparator.comparingInt(ProgramMatchingResponse.WaitingTeam::waitingDays).reversed())
				.toList();
		return new ProgramMatchingResponse.Overview(
				waiting,
				matches(mentorships, MATCHED),
				matches(mentorships, ENDED));
	}

	private ProgramMatchingResponse.WaitingTeam waitingTeam(Organization team, ProgramStartup startup, LocalDate today) {
		Need need = needOf(team);
		LocalDate joined = startup.getCreatedAt().atZone(MeetingSlotTimes.APP_ZONE).toLocalDate();
		return new ProgramMatchingResponse.WaitingTeam(
				team.getId(),
				team.getName(),
				team.getSector() == null ? null : team.getSector().value(),
				team.getMaturity() == null ? null : team.getMaturity().value(),
				team.getFounder() == null ? null : team.getFounder().getFullName(),
				(int) Math.max(0, ChronoUnit.DAYS.between(joined, today)),
				need.source(),
				need.topic(),
				need.text());
	}

	private static List<ProgramMatchingResponse.Match> matches(List<Mentorship> mentorships, Set<MentorshipStatus> statuses) {
		return mentorships.stream()
				.filter(mentorship -> statuses.contains(mentorship.getStatus()))
				.sorted(Comparator.comparing(Mentorship::getStartDate).reversed())
				.map(mentorship -> new ProgramMatchingResponse.Match(
						mentorship.getId(),
						mentorship.getOrganization().getId(),
						mentorship.getOrganization().getName(),
						mentorship.getMentor().getId(),
						mentorship.getMentor().getFullName(),
						mentorship.getStatus(),
						mentorship.getStartDate(),
						mentorship.getEndDate()))
				.toList();
	}

	/** The team's latest open request says what they need; otherwise their startup profile's support areas. */
	private Need needOf(Organization team) {
		Optional<MentorshipRequest> request = mentorshipRequestRepository
				.findByOrganizationIdOrderByCreatedAtDesc(team.getId()).stream()
				.filter(value -> OPEN_REQUEST.contains(value.getStatus()))
				.findFirst();
		if (request.isPresent()) {
			return new Need(MatchNeedSource.REQUEST, request.get().getSupportArea(), request.get().getSummary());
		}
		List<String> areas = team.getSupportAreas() == null ? List.of() : team.getSupportAreas();
		if (!areas.isEmpty()) {
			return new Need(MatchNeedSource.PROFILE, String.join(", ", areas),
					firstPresent(team.getStatusSummary(), team.getDescription()));
		}
		return new Need(MatchNeedSource.NONE, null, null);
	}

	/** A request to the matched mentor counts as accepted; the ones to other mentors are withdrawn. */
	private void closeOpenRequests(Organization team, UUID mentorId) {
		for (MentorshipRequest request : mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(team.getId())) {
			if (!OPEN_REQUEST.contains(request.getStatus())) {
				continue;
			}
			if (request.getMentor() != null && request.getMentor().getId().equals(mentorId)) {
				request.setStatus(MentorshipRequestStatus.ACCEPTED);
			} else {
				request.withdraw();
			}
			mentorshipRequestRepository.save(request);
		}
	}

	private Organization requireTeam(Program program, UUID organizationId) {
		programStartupRepository.findByProgramIdAndOrganizationId(program.getId(), organizationId)
				.orElseThrow(() -> new ResourceNotFoundException("Team is not in this program: " + organizationId));
		Organization team = organizationRepository.findById(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException("Team not found: " + organizationId));
		if (!team.isApprovedForProgram()) {
			throw new InvalidRequestException(Texts.forRequest("error.startupNotApproved"));
		}
		return team;
	}

	private List<UUID> poolOf(Program program) {
		return programMentorRepository.findByProgramIdOrderByCreatedAtAsc(program.getId()).stream()
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
				.map(ProgramMentor::getMentorId)
				.toList();
	}

	private static String firstPresent(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return GENERAL_TOPIC;
	}

	private record Need(MatchNeedSource source, String topic, String text) {
	}

	private record SuggestionInput(List<UUID> pool, RecommendMentorsRequest request, int defaultCapacity) {
	}
}
