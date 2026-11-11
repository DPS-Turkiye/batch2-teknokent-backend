package com.dps.teknokent.service;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamMembership;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamMembershipRepository;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.RequiredArgsConstructor;

/**
 * Who may read a person's account and profile (e-mail, phone, bio). Anyone may read their own, even while
 * pending; otherwise the caller must be approved and an admin, or share something the app already shows them the
 * person through: the same startup, a mentorship request or mentorship between the person's startup and a mentor,
 * a program both take part in, or a program one of them coordinates. Coordinators are scoped like the admin
 * profile summary: startup-mentoring programs they run, never through their own pool invites, and never other
 * staff. Everyone else gets "not found", so the endpoints do not confirm who exists. Phone numbers and contact
 * preferences stay with the person and admins: no screen shows them to anyone else.
 */
@Service
@RequiredArgsConstructor
public class PersonAccessService {

	private final DomainLookupService domainLookupService;
	private final TeamMembershipRepository teamMembershipRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final MentorshipRepository mentorshipRepository;
	private final AppUserRepository appUserRepository;

	/** A declined or withdrawn request no longer ties a mentor to the startup. */
	private static final Set<MentorshipRequestStatus> LIVE_REQUESTS = EnumSet.of(MentorshipRequestStatus.PENDING,
			MentorshipRequestStatus.MORE_INFO_REQUESTED, MentorshipRequestStatus.COUNTER_PROPOSED,
			MentorshipRequestStatus.ACCEPTED);

	@Transactional(readOnly = true)
	public AppUser requireVisible(UUID actorId, UUID personId) {
		AppUser person = domainLookupService.requireUser(personId);
		if (personId.equals(actorId)) {
			return person;
		}
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getStatus() != UserAccountStatus.APPROVED || !related(actor, person)) {
			throw new ResourceNotFoundException("User not found: " + personId);
		}
		return person;
	}

	/** Whether the caller also gets the person's phone and contact preferences: only they and approved admins. */
	@Transactional(readOnly = true)
	public boolean seesContactDetails(UUID actorId, UUID personId) {
		if (personId.equals(actorId)) {
			return true;
		}
		AppUser actor = domainLookupService.requireUser(actorId);
		return actor.getStatus() == UserAccountStatus.APPROVED && actor.has(UserRole.ADMIN);
	}

	/** The account list: approved admins only. */
	@Transactional(readOnly = true)
	public void requireAdmin(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getStatus() != UserAccountStatus.APPROVED || !actor.has(UserRole.ADMIN)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can list accounts");
		}
	}

	private boolean related(AppUser actor, AppUser person) {
		if (actor.has(UserRole.ADMIN) || sameStartup(actor, person)
				|| mentorOf(actor, person) || mentorOf(person, actor)) {
			return true;
		}
		Set<UUID> actorPrograms = memberPrograms(actor);
		Set<UUID> personPrograms = memberPrograms(person);
		if (actorPrograms.stream().anyMatch(personPrograms::contains)) {
			return true;
		}
		return coordinatesProgramOf(actor, person) || coordinatesAnyOf(person, actorPrograms);
	}

	private static boolean sameStartup(AppUser actor, AppUser person) {
		Organization startup = actor.getOrganization();
		return startup != null && !startup.isPersonal() && person.getOrganization() != null
				&& startup.getId().equals(person.getOrganization().getId());
	}

	/** A live request the person's startup (or personal team) sent the mentor, or a mentorship between them. */
	private boolean mentorOf(AppUser mentor, AppUser person) {
		Organization organization = person.getOrganization();
		if (!mentor.has(UserRole.MENTOR) || organization == null) {
			return false;
		}
		return mentorshipRequestRepository.existsByMentorIdAndOrganizationIdAndStatusIn(mentor.getId(),
				organization.getId(), LIVE_REQUESTS)
				|| mentorshipRepository.existsByMentorIdAndOrganizationId(mentor.getId(), organization.getId());
	}

	/** Programs the user is in now: their own, their current team's, and the mentor pools that accepted them. */
	private Set<UUID> memberPrograms(AppUser user) {
		Set<UUID> ids = new LinkedHashSet<>();
		if (user.getProgramId() != null) {
			ids.add(user.getProgramId());
		}
		if (user.getOrganization() != null) {
			ids.addAll(programsOfStartup(user.getOrganization().getId()));
		}
		if (user.has(UserRole.MENTOR)) {
			pools(user).stream()
					.filter(row -> row.getStatus() == ProgramMentorStatus.ACCEPTED)
					.map(ProgramMentor::getProgramId)
					.forEach(ids::add);
		}
		return ids;
	}

	/**
	 * Team history's reach (every startup the person was in) and the mentor's own requests to join a pool. A
	 * coordinator's open invite does not count: any coordinator can send one to any mentor.
	 */
	private boolean coordinatesProgramOf(AppUser coordinator, AppUser person) {
		if (!coordinator.has(UserRole.COORDINATOR) || person.has(UserRole.ADMIN)
				|| person.has(UserRole.COORDINATOR)) {
			return false;
		}
		Set<UUID> ids = memberPrograms(person);
		for (TeamMembership stay : teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(person.getId())) {
			ids.addAll(programsOfStartup(stay.getOrganization().getId()));
		}
		if (person.has(UserRole.MENTOR)) {
			pools(person).stream()
					.filter(row -> row.getStatus() == ProgramMentorStatus.PENDING
							&& row.getInvitedBy() == ProgramMentorInvitedBy.MENTOR)
					.map(ProgramMentor::getProgramId)
					.forEach(ids::add);
		}
		return programs(ids).stream()
				.filter(program -> program.getProgramType() == ProgramType.STARTUP_MENTORING)
				.anyMatch(program -> ProgramCoordination.coordinates(program, coordinator.getId(),
						programCoordinatorRepository));
	}

	/**
	 * The program page names the people who run it, so its members may open them; an admin who only created the
	 * program is not one of them once it has coordinators.
	 */
	private boolean coordinatesAnyOf(AppUser person, Set<UUID> programIds) {
		return ProgramCoordination.runners(programs(programIds), programCoordinatorRepository, appUserRepository)
				.values().stream()
				.anyMatch(runners -> runners.stream().anyMatch(runner -> runner.getId().equals(person.getId())));
	}

	private List<Program> programs(Set<UUID> ids) {
		return ids.isEmpty() ? List.of() : programRepository.findAllById(ids);
	}

	private List<UUID> programsOfStartup(UUID organizationId) {
		return programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
				.map(ProgramStartup::getProgramId)
				.toList();
	}

	private List<ProgramMentor> pools(AppUser mentor) {
		return programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentor.getId());
	}
}
