package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.PersonProgramRelation;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.PersonSummaryResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamMembershipRepository;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.RequiredArgsConstructor;

/**
 * The profile page admins and coordinators open from their lists, for mentors and mentees only. An approved admin
 * sees any of them; an approved coordinator only people in a startup-mentoring (Teknokent) program they run (its
 * mentor pool, or the mentee's program reach as with team history), and only those programs. Everyone else gets
 * "not found", so the endpoint does not confirm who exists.
 */
@Service
@RequiredArgsConstructor
public class PersonSummaryService {

	private final DomainLookupService domainLookupService;
	private final MentorProfileService mentorProfileService;
	private final MentorProfileRepository mentorProfileRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final TeamMembershipRepository teamMembershipRepository;
	private final TeamMembershipService teamMembershipService;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;

	@Transactional(readOnly = true)
	public PersonSummaryResponse summary(UUID actorId, UUID personId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		AppUser person = domainLookupService.requireUser(personId);
		boolean mentor = person.has(UserRole.MENTOR);
		boolean mentee = person.has(UserRole.MENTEE);
		boolean admin = actor.has(UserRole.ADMIN);
		// The proxy's session cookie can outlive an approval being withdrawn, so the status is checked here.
		boolean approvedStaff = actor.getStatus() == UserAccountStatus.APPROVED
				&& (admin || actor.has(UserRole.COORDINATOR));
		boolean staffTarget = person.has(UserRole.ADMIN) || person.has(UserRole.COORDINATOR);
		if (!(mentor || mentee) || !approvedStaff || (staffTarget && !admin)) {
			throw notFound(personId);
		}

		Map<UUID, ProgramMentor> pools = mentor ? pools(personId) : Map.of();
		Organization startup = currentStartup(person);
		Set<UUID> menteeShown = mentee ? menteePrograms(person, startup) : Set.of();
		Set<UUID> menteeAccess = mentee
				? teamMembershipService.programReach(person,
						teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(personId))
				: Set.of();

		Set<UUID> ids = new LinkedHashSet<>(pools.keySet());
		ids.addAll(menteeAccess);
		List<Program> programs = programRepository.findAllById(ids).stream()
				.filter(program -> program.getProgramType() == ProgramType.STARTUP_MENTORING)
				.filter(program -> admin
						|| ProgramCoordination.coordinates(program, actorId, programCoordinatorRepository))
				.toList();
		if (!admin && programs.isEmpty()) {
			throw notFound(personId);
		}

		List<PersonSummaryResponse.Program> rows = new ArrayList<>();
		for (Program program : programs) {
			ProgramMentor pool = pools.get(program.getId());
			if (pool != null) {
				rows.add(new PersonSummaryResponse.Program(program.getId(), program.getName(), program.getStatus(),
						PersonProgramRelation.MENTOR, pool.getStatus()));
			}
			if (menteeShown.contains(program.getId())) {
				rows.add(new PersonSummaryResponse.Program(program.getId(), program.getName(), program.getStatus(),
						PersonProgramRelation.MENTEE, null));
			}
		}
		rows.sort(Comparator.comparing(PersonSummaryResponse.Program::name, String.CASE_INSENSITIVE_ORDER));

		PersonSummaryResponse.Mentor mentorProfile = mentor && mentorProfileRepository.existsById(personId)
				? PersonSummaryResponse.Mentor.of(mentorProfileService.findById(personId))
				: null;
		return PersonSummaryResponse.of(person, mentorProfile,
				startup == null ? null : PersonSummaryResponse.Startup.of(startup), rows);
	}

	/**
	 * Accepted pool rows, plus the mentor's own pending requests to join. A coordinator's open invite does not
	 * count: any coordinator can send one to any approved mentor, so it would open every mentor's profile.
	 */
	private Map<UUID, ProgramMentor> pools(UUID mentorId) {
		return programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentorId).stream()
				.filter(row -> row.getStatus() == ProgramMentorStatus.ACCEPTED
						|| (row.getStatus() == ProgramMentorStatus.PENDING
								&& row.getInvitedBy() == ProgramMentorInvitedBy.MENTOR))
				.collect(Collectors.toMap(ProgramMentor::getProgramId, Function.identity(), (first, next) -> first,
						LinkedHashMap::new));
	}

	private static Organization currentStartup(AppUser person) {
		Organization organization = person.getOrganization();
		return organization == null || organization.isPersonal() ? null : organization;
	}

	/** The programs the page lists for a mentee: their own program and their current startup's programs. */
	private Set<UUID> menteePrograms(AppUser person, Organization startup) {
		Set<UUID> ids = new LinkedHashSet<>();
		if (person.getProgramId() != null) {
			ids.add(person.getProgramId());
		}
		if (startup != null) {
			programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(startup.getId()).stream()
					.map(ProgramStartup::getProgramId)
					.forEach(ids::add);
		}
		return ids;
	}

	private static ResourceNotFoundException notFound(UUID personId) {
		return new ResourceNotFoundException("User not found: " + personId);
	}
}
