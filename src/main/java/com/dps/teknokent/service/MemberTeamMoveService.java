package com.dps.teknokent.service;

import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.TeamMoveBlock;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.MemberTeamMoveOptionsResponse;
import com.dps.teknokent.dto.responses.MemberTeamMoveResponse;
import com.dps.teknokent.dto.responses.TeamMoveTeamResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamChangeRequest;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationPendingMemberRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Moves one team member to another team at once, with no step for anyone to accept. Admins move anyone anywhere; a
 * coordinator only between teams that are both in programs they run. Only the person goes: the old team keeps its
 * requests, mentorships, meetings, tasks and documents (their open tasks are handed over as when someone leaves),
 * and their team history records the move. A coordinator asking about anyone outside their programs gets a 404, as
 * if the person or team did not exist. A founder moves only when nobody else is in the startup: it is then archived
 * as when a founder leaves alone, and its unanswered requests are withdrawn because nobody is left to follow them up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberTeamMoveService {

	static final String FOUNDER_CODE = "TEAM_FOUNDER";
	private static final String TEAM_HREF = "/startup/team";

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final OrganizationPendingMemberRepository pendingMemberRepository;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final TeamChangeRequestRepository teamChangeRequestRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final TeamChangeService teamChangeService;
	private final TeamMembershipService teamMembershipService;
	private final TaskHandoverService taskHandoverService;
	private final NotificationService notificationService;

	@Transactional(readOnly = true)
	public MemberTeamMoveOptionsResponse options(UUID actorId, UUID userId) {
		Mover mover = requireMover(actorId);
		AppUser person = appUserRepository.findById(userId).orElseThrow(() -> personNotFound(userId));
		Organization current = person.getOrganization();
		requireReach(mover, current == null ? null : current.getId(), userId);
		TeamMoveBlock block = blockOf(person, current);
		TeamMoveTeamResponse currentTeam = null;
		List<TeamMoveTeamResponse> targets = List.of();
		if (current != null && !current.isPersonal()) {
			currentTeam = describe(List.of(current), mover).get(0);
			if (block == null) {
				targets = targets(mover, current);
			}
		}
		boolean archives = block == null && current != null && isFounder(person, current);
		return new MemberTeamMoveOptionsResponse(person.getId(), person.getFullName(), currentTeam, block, archives,
				targets);
	}

	@Transactional
	public MemberTeamMoveResponse move(UUID actorId, UUID userId, UUID targetOrganizationId) {
		Mover mover = requireMover(actorId);
		// A coordinator's reach is settled before any lock, so nobody can hold rows outside their programs.
		if (!mover.admin()) {
			UUID teamId = appUserRepository.findOrganizationIdById(userId).orElseThrow(() -> personNotFound(userId));
			requireReach(mover, teamId, userId);
			requireTargetReach(mover, targetOrganizationId);
		}
		// The person, then both teams in id order. Each row is first read under its lock, so the checks below see
		// what is committed: a founder handover or another move that got there first is not missed.
		AppUser person = appUserRepository.findByIdForUpdate(userId).orElseThrow(() -> personNotFound(userId));
		Organization current = person.getOrganization();
		requireReach(mover, current == null ? null : current.getId(), userId);
		refuse(personBlock(person, current));
		if (current == null || current.getId().equals(targetOrganizationId)) {
			throw new InvalidRequestException(Texts.forRequest("error.memberMoveSameTeam"));
		}
		Map<UUID, Organization> locked = lockInOrder(current.getId(), targetOrganizationId);
		Organization from = locked.get(current.getId());
		Organization to = locked.get(targetOrganizationId);
		if (from == null || to == null) {
			throw new ResourceNotFoundException("Team not found: " + targetOrganizationId);
		}
		// Asked again now the rows are held: the target may have left the coordinator's programs while we waited.
		requireTargetReach(mover, to.getId());
		refuse(teamBlock(person, from));
		if (!isOpenTeam(to) || programsOf(to.getId()).isEmpty()) {
			throw new InvalidRequestException(Texts.forRequest("error.memberMoveTargetClosed"));
		}

		// Only a founder who is alone gets past teamBlock, so the startup is empty once they go.
		boolean archives = isFounder(person, from);
		Instant movedAt = Instant.now();
		Set<UUID> fromPrograms = programsOf(from.getId());
		teamMembershipService.left(person, from, TeamExitReason.MOVED);
		cancelWaitingTeamChanges(person, from, movedAt);
		taskHandoverService.markLeft(person, from);
		List<MentorshipRequest> withdrawn = List.of();
		if (archives) {
			withdrawn = withdrawOpenRequests(from);
			teamChangeService.archiveEmptied(from, person);
		}
		followTeamProgram(person, fromPrograms, to);
		person.setOrganization(to);
		appUserRepository.save(person);
		teamMembershipService.joined(person, to);
		pendingMemberRepository.deleteByOrganizationIdAndEmailIgnoreCase(to.getId(), person.getEmail());

		List<TeamMoveTeamResponse> teams = describe(List.of(from, to), mover);
		notifyEveryone(person, from, to);
		notifyMentors(from, withdrawn);
		log.info("Team move: user {} from organization {} to {} by {}{}", person.getId(), from.getId(), to.getId(),
				mover.actor().getId(), archives ? "; the emptied organization was archived" : "");
		return new MemberTeamMoveResponse(person.getId(), teams.get(0), teams.get(1), person.getProgramId(),
				archives, withdrawn.size(), movedAt);
	}

	private record Mover(AppUser actor, boolean admin) {
	}

	/** An approved admin or coordinator; anyone else may not move people at all. */
	private Mover requireMover(UUID actorId) {
		AppUser actor = appUserRepository.findById(actorId).orElse(null);
		if (actor == null || actor.getStatus() != UserAccountStatus.APPROVED
				|| (!actor.has(UserRole.ADMIN) && !actor.has(UserRole.COORDINATOR))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.memberMoveForbidden"));
		}
		return new Mover(actor, actor.has(UserRole.ADMIN));
	}

	/** A coordinator sees only people whose current team is in one of their programs. */
	private void requireReach(Mover mover, @Nullable UUID currentTeamId, UUID userId) {
		if (!mover.admin() && (currentTeamId == null || !coordinates(mover, currentTeamId))) {
			throw personNotFound(userId);
		}
	}

	/** A coordinator moves people only into teams in their own programs; any other team "does not exist". */
	private void requireTargetReach(Mover mover, UUID targetOrganizationId) {
		if (!mover.admin() && !coordinates(mover, targetOrganizationId)) {
			throw new ResourceNotFoundException("Team not found: " + targetOrganizationId);
		}
	}

	private boolean coordinates(Mover mover, UUID organizationId) {
		return ProgramCoordination.coordinatesStartup(organizationId, mover.actor().getId(), programStartupRepository,
				programRepository, programCoordinatorRepository);
	}

	private @Nullable TeamMoveBlock blockOf(AppUser person, @Nullable Organization team) {
		TeamMoveBlock block = personBlock(person, team);
		return block != null || team == null ? block : teamBlock(person, team);
	}

	/** What the person's own row decides; it never reads the team's state, which is only read under its lock. */
	private static @Nullable TeamMoveBlock personBlock(AppUser person, @Nullable Organization team) {
		if (person.getStatus() != UserAccountStatus.APPROVED) {
			return TeamMoveBlock.NOT_APPROVED;
		}
		if (!person.isTeamMemberAccount()) {
			return TeamMoveBlock.NOT_A_TEAM_MEMBER;
		}
		return team == null ? TeamMoveBlock.NO_TEAM : null;
	}

	/** A founder with others in the startup must hand it over first ("Kurucuyu değiştir"); one alone may go. */
	private @Nullable TeamMoveBlock teamBlock(AppUser person, Organization team) {
		if (team.isPersonal()) {
			return TeamMoveBlock.NO_TEAM;
		}
		if (isFounder(person, team) && !isAlone(person, team)) {
			return TeamMoveBlock.FOUNDER;
		}
		return null;
	}

	private static boolean isFounder(AppUser person, Organization team) {
		return team.getFounder() != null && team.getFounder().getId().equals(person.getId());
	}

	/**
	 * Nobody else is in the startup: every other account that has it as its organization was rejected. Someone
	 * still waiting for approval counts, since they may yet join.
	 */
	private boolean isAlone(AppUser person, Organization team) {
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(team.getId()).stream()
				.allMatch(member -> member.getId().equals(person.getId())
						|| member.getStatus() == UserAccountStatus.REJECTED);
	}

	/** An emptied startup's requests still waiting for a mentor have nobody left to follow them up. */
	private List<MentorshipRequest> withdrawOpenRequests(Organization team) {
		List<MentorshipRequest> open = mentorshipRequestRepository
				.findByOrganizationIdOrderByCreatedAtDesc(team.getId())
				.stream()
				.filter(request -> request.getStatus().isOpen())
				.toList();
		open.forEach(MentorshipRequest::withdraw);
		return mentorshipRequestRepository.saveAll(open);
	}

	/** One notice per mentor, however many of the startup's requests were waiting on them. */
	private void notifyMentors(Organization team, List<MentorshipRequest> withdrawn) {
		Map<UUID, AppUser> mentors = new LinkedHashMap<>();
		withdrawn.forEach(request -> mentors.putIfAbsent(request.getMentor().getId(), request.getMentor()));
		mentors.values().stream()
				.filter(mentor -> mentor.getStatus() != UserAccountStatus.DELETED)
				.forEach(mentor -> notificationService.create(mentor,
						Texts.get(mentor, "notification.requestClosedByTeamArchived.title"),
						Texts.get(mentor, "notification.requestClosedByTeamArchived.body", team.getName()),
						"/mentor-dashboard"));
	}

	private static void refuse(@Nullable TeamMoveBlock block) {
		if (block == null) {
			return;
		}
		switch (block) {
		case FOUNDER -> throw new ConflictException(Texts.forRequest("error.memberMoveFounder"), FOUNDER_CODE);
		case NOT_APPROVED -> throw new InvalidRequestException(Texts.forRequest("error.memberMoveNotApproved"));
		case NOT_A_TEAM_MEMBER -> throw new InvalidRequestException(Texts.forRequest("error.memberMoveNotMember"));
		case NO_TEAM -> throw new InvalidRequestException(Texts.forRequest("error.memberMoveNoTeam"));
		}
	}

	/**
	 * A team someone can be put in: approved, not archived, not personal. It must also be in a program (checked
	 * separately), so the person's program can follow it and is never silently dropped.
	 */
	private static boolean isOpenTeam(Organization team) {
		return !team.isPersonal() && team.isApprovedForProgram();
	}

	/**
	 * Both teams, locked in id order so two opposite moves cannot wait on each other. The person is locked
	 * first, as user deletion does; a whole-team program move locks the team first, and Postgres ends either
	 * one if the two ever meet on the same person, which only an admin can retry.
	 */
	private Map<UUID, Organization> lockInOrder(UUID first, UUID second) {
		Map<UUID, Organization> locked = new HashMap<>();
		Stream.of(first, second)
				.sorted()
				.forEach(id -> organizationRepository.findByIdForUpdate(id)
						.ifPresent(team -> locked.put(id, team)));
		return locked;
	}

	private List<TeamMoveTeamResponse> targets(Mover mover, Organization current) {
		List<Organization> candidates;
		if (mover.admin()) {
			candidates = organizationRepository.findAllWithFounder();
		} else {
			List<UUID> programIds = ProgramCoordination
					.programsOf(mover.actor().getId(), programRepository, programCoordinatorRepository).stream()
					.map(Program::getId)
					.toList();
			Set<UUID> teamIds = programStartupRepository.findByProgramIdIn(programIds).stream()
					.map(ProgramStartup::getOrganizationId)
					.collect(Collectors.toSet());
			candidates = organizationRepository.findAllWithFounderByIdIn(teamIds);
		}
		Collator turkish = Collator.getInstance(Locale.forLanguageTag("tr"));
		return describe(candidates.stream()
				.filter(MemberTeamMoveService::isOpenTeam)
				.filter(team -> !team.getId().equals(current.getId()))
				.sorted(Comparator.comparing(Organization::getName, turkish))
				.toList(), mover).stream()
				.filter(team -> team.programId() != null)
				.toList();
	}

	/**
	 * Each team with the program it is shown under: its active program, else the last it joined. A coordinator sees
	 * the team under one of their own programs when it is also in someone else's.
	 */
	private List<TeamMoveTeamResponse> describe(List<Organization> teams, Mover mover) {
		Map<UUID, List<ProgramStartup>> joined = programStartupRepository
				.findByOrganizationIdIn(teams.stream().map(Organization::getId).toList()).stream()
				.sorted(Comparator.comparing(ProgramStartup::getCreatedAt,
						Comparator.nullsFirst(Comparator.naturalOrder())))
				.collect(Collectors.groupingBy(ProgramStartup::getOrganizationId, LinkedHashMap::new,
						Collectors.toList()));
		Set<UUID> programIds = joined.values().stream()
				.flatMap(List::stream)
				.map(ProgramStartup::getProgramId)
				.collect(Collectors.toSet());
		Map<UUID, Program> programs = programRepository.findAllById(programIds).stream()
				.collect(Collectors.toMap(Program::getId, Function.identity()));
		return teams.stream().map(team -> {
			Program shown = shownProgram(team, joined.getOrDefault(team.getId(), List.of()), programs, mover);
			return new TeamMoveTeamResponse(team.getId(), team.getName(), shown == null ? null : shown.getId(),
					shown == null ? null : shown.getName());
		}).toList();
	}

	private @Nullable Program shownProgram(Organization team, List<ProgramStartup> joined,
			Map<UUID, Program> programs, Mover mover) {
		List<Program> in = new ArrayList<>();
		for (ProgramStartup row : joined) {
			Program program = programs.get(row.getProgramId());
			if (program != null) {
				in.add(program);
			}
		}
		if (!mover.admin()) {
			List<Program> own = in.stream()
					.filter(program -> ProgramCoordination.coordinates(program, mover.actor().getId(),
							programCoordinatorRepository))
					.toList();
			if (!own.isEmpty()) {
				in = own;
			}
		}
		for (Program program : in) {
			if (program.getId().equals(team.getActiveProgramId())) {
				return program;
			}
		}
		return in.isEmpty() ? null : in.get(in.size() - 1);
	}

	private Set<UUID> programsOf(UUID organizationId) {
		return programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
				.map(ProgramStartup::getProgramId)
				.collect(Collectors.toSet());
	}

	/**
	 * The person's own program follows the team, as when a whole team changes program, so they are not left in the
	 * old team's program. Someone who signed up into an unrelated program, or whose program the new team is in
	 * too, keeps it; someone without one gets the new team's, as leaving a team fills it from the old one.
	 */
	private void followTeamProgram(AppUser person, Set<UUID> fromPrograms, Organization to) {
		UUID own = person.getProgramId();
		if (own != null && !fromPrograms.contains(own)) {
			return;
		}
		List<ProgramStartup> joined = programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(to.getId());
		if (joined.isEmpty() || (own != null && joined.stream().anyMatch(row -> own.equals(row.getProgramId())))) {
			return;
		}
		UUID active = to.getActiveProgramId();
		boolean activeJoined = active != null && joined.stream().anyMatch(row -> active.equals(row.getProgramId()));
		person.setProgramId(activeJoined ? active : joined.get(joined.size() - 1).getProgramId());
	}

	/** A leave or removal the person was waiting on in the old team has nothing left to decide. */
	private void cancelWaitingTeamChanges(AppUser person, Organization from, Instant now) {
		List<TeamChangeRequest> waiting = teamChangeRequestRepository
				.findByOrganizationIdAndStatusOrderByCreatedAtAsc(from.getId(), TeamChangeStatus.PENDING).stream()
				.filter(request -> request.getMember().getId().equals(person.getId()))
				.toList();
		waiting.forEach(request -> {
			request.setStatus(TeamChangeStatus.CANCELLED);
			request.setDecidedAt(now);
		});
		teamChangeRequestRepository.saveAll(waiting);
	}

	private void notifyEveryone(AppUser person, Organization from, Organization to) {
		notificationService.create(person, Texts.get(person, "notification.memberMoved.title"),
				Texts.get(person, "notification.memberMoved.body", from.getName(), to.getName()), TEAM_HREF);
		notifyFounder(from.getFounder(), person, "notification.memberMovedOut", from, to);
		notifyFounder(to.getFounder(), person, "notification.memberMovedIn", from, to);
	}

	private void notifyFounder(@Nullable AppUser founder, AppUser person, String key, Organization from,
			Organization to) {
		if (founder == null || founder.getId().equals(person.getId())
				|| founder.getStatus() == UserAccountStatus.DELETED) {
			return;
		}
		notificationService.create(founder, Texts.get(founder, key + ".title", person.getFullName()),
				Texts.get(founder, key + ".body", person.getFullName(), from.getName(), to.getName()), TEAM_HREF);
	}

	private static ResourceNotFoundException personNotFound(UUID userId) {
		return new ResourceNotFoundException("User not found: " + userId);
	}
}
