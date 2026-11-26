package com.dps.teknokent.service;

import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.FounderChangeOptionsResponse;
import com.dps.teknokent.dto.responses.FounderChangeResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
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
 * "Kurucuyu değiştir": an admin, or a coordinator who runs a program the startup is in, makes another current,
 * approved member its founder. The old founder stays in the team as a member. Team history keeps stays, not roles,
 * so the change is logged with ids only; the old founder, the new one and the startup's coordinators are told. A
 * coordinator asking about a startup outside their programs gets a 404, as if it did not exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FounderChangeService {

	private static final String TEAM_HREF = "/startup/team";

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final TeamChangeRequestRepository teamChangeRequestRepository;
	private final NotificationService notificationService;

	@Transactional(readOnly = true)
	public FounderChangeOptionsResponse options(UUID actorId, UUID organizationId) {
		AppUser actor = requireChanger(actorId);
		requireReach(actor, organizationId);
		Organization team = organizationRepository.findById(organizationId)
				.filter(found -> !found.isPersonal())
				.orElseThrow(() -> teamNotFound(organizationId));
		AppUser founder = team.getFounder();
		List<FounderChangeOptionsResponse.Person> candidates = team.isArchived() ? List.of()
				: appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId).stream()
						.filter(member -> isCandidate(member, team))
						.sorted(Comparator.comparing(AppUser::getFullName,
								Collator.getInstance(Locale.forLanguageTag("tr"))))
						.map(FounderChangeService::person)
						.toList();
		return new FounderChangeOptionsResponse(team.getId(), team.getName(),
				founder == null ? null : person(founder), candidates);
	}

	@Transactional
	public FounderChangeResponse change(UUID actorId, UUID organizationId, UUID newFounderId) {
		AppUser actor = requireChanger(actorId);
		// Settled before any lock, so nobody can hold rows of a startup outside their programs.
		requireReach(actor, organizationId);
		// The person, then the startup, in the order a member move takes them; each is first read under its lock,
		// so a move of the new founder out of the team that got there first is seen.
		AppUser successor = appUserRepository.findByIdForUpdate(newFounderId).orElse(null);
		Organization team = organizationRepository.findByIdForUpdate(organizationId)
				.filter(found -> !found.isPersonal())
				.orElseThrow(() -> teamNotFound(organizationId));
		if (team.isArchived()) {
			throw new InvalidRequestException(Texts.forRequest("error.founderChangeTeamClosed"));
		}
		AppUser previous = team.getFounder();
		if (successor != null && previous != null && previous.getId().equals(successor.getId())) {
			throw new InvalidRequestException(Texts.forRequest("error.founderChangeSamePerson"));
		}
		if (successor == null || !isCandidate(successor, team)) {
			throw new InvalidRequestException(Texts.forRequest("error.founderChangeNotMember"));
		}
		if (teamChangeRequestRepository.existsByMemberIdAndOrganizationIdAndStatus(successor.getId(),
				organizationId, TeamChangeStatus.PENDING)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamSuccessorLeaving"));
		}

		team.setFounder(successor);
		organizationRepository.save(team);
		Instant changedAt = Instant.now();
		notifyEveryone(actor, team, previous, successor);
		log.info("Founder change: organization {} from {} to {} by {}", team.getId(),
				previous == null ? "nobody" : previous.getId(), successor.getId(), actor.getId());
		return new FounderChangeResponse(team.getId(), previous == null ? null : previous.getId(),
				successor.getId(), changedAt);
	}

	/** An approved admin or coordinator; anyone else may not change a startup's founder. */
	private AppUser requireChanger(UUID actorId) {
		AppUser actor = appUserRepository.findById(actorId).orElse(null);
		if (actor == null || actor.getStatus() != UserAccountStatus.APPROVED
				|| (!actor.has(UserRole.ADMIN) && !actor.has(UserRole.COORDINATOR))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					Texts.forRequest("error.founderChangeForbidden"));
		}
		return actor;
	}

	/** A coordinator reaches only startups in a program they run; any other startup "does not exist". */
	private void requireReach(AppUser actor, UUID organizationId) {
		if (!actor.has(UserRole.ADMIN) && !ProgramCoordination.coordinatesStartup(organizationId, actor.getId(),
				programStartupRepository, programRepository, programCoordinatorRepository)) {
			throw teamNotFound(organizationId);
		}
	}

	/** Someone who can lead the startup: an approved team member in it now, other than its founder. */
	private static boolean isCandidate(AppUser member, Organization team) {
		Organization in = member.getOrganization();
		AppUser founder = team.getFounder();
		return in != null && in.getId().equals(team.getId())
				&& (founder == null || !founder.getId().equals(member.getId()))
				&& member.getStatus() == UserAccountStatus.APPROVED
				&& member.isTeamMemberAccount();
	}

	private void notifyEveryone(AppUser actor, Organization team, @Nullable AppUser previous, AppUser successor) {
		notificationService.createAndMail(successor, Texts.get(successor, "notification.founderChangedNew.title"),
				Texts.get(successor, "notification.founderChangedNew.body", team.getName()), TEAM_HREF);
		if (previous != null && previous.getStatus() != UserAccountStatus.DELETED) {
			notificationService.createAndMail(previous,
					Texts.get(previous, "notification.founderChangedPrevious.title"),
					Texts.get(previous, "notification.founderChangedPrevious.body", team.getName(),
							successor.getFullName()),
					TEAM_HREF);
		}
		for (Runner runner : runnersOf(team)) {
			AppUser coordinator = runner.coordinator();
			if (!coordinator.getId().equals(actor.getId())) {
				notificationService.createAndMail(coordinator,
						Texts.get(coordinator, "notification.founderChangedCoordinator.title"),
						Texts.get(coordinator, "notification.founderChangedCoordinator.body", team.getName(),
								successor.getFullName()),
						"/coordinator/programs/" + runner.programId() + "/teams");
			}
		}
	}

	private record Runner(AppUser coordinator, UUID programId) {
	}

	/** Everyone who runs a program the startup is in, once each, with the first such program to link to. */
	private List<Runner> runnersOf(Organization team) {
		List<UUID> programIds = programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(team.getId())
				.stream()
				.map(ProgramStartup::getProgramId)
				.toList();
		List<Program> programs = programRepository.findAllById(programIds);
		Map<UUID, List<AppUser>> byProgram = ProgramCoordination.runners(programs, programCoordinatorRepository,
				appUserRepository);
		Set<UUID> seen = new HashSet<>();
		List<Runner> runners = new ArrayList<>();
		for (Program program : programs) {
			for (AppUser coordinator : byProgram.getOrDefault(program.getId(), List.of())) {
				if (seen.add(coordinator.getId())) {
					runners.add(new Runner(coordinator, program.getId()));
				}
			}
		}
		return runners;
	}

	private static FounderChangeOptionsResponse.Person person(AppUser user) {
		return new FounderChangeOptionsResponse.Person(user.getId(), user.getFullName(), user.getJobTitle());
	}

	private static ResourceNotFoundException teamNotFound(UUID organizationId) {
		return new ResourceNotFoundException("Team not found: " + organizationId);
	}
}
