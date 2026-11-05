package com.dps.teknokent.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.TeamHistoryResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamMembership;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamMembershipRepository;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.RequiredArgsConstructor;

/** Records who was in which startup and when; the history is for admins and the person's coordinators only. */
@Service
@RequiredArgsConstructor
public class TeamMembershipService {

	private final TeamMembershipRepository teamMembershipRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final DomainLookupService domainLookupService;

	/** Call after the person is saved into {@code organization}. */
	public void joined(AppUser user, Organization organization) {
		TeamMembership membership = new TeamMembership();
		membership.setUserId(user.getId());
		membership.setOrganization(organization);
		membership.setJoinedAt(Instant.now());
		teamMembershipRepository.save(membership);
	}

	public void left(AppUser user, Organization organization, TeamExitReason reason) {
		teamMembershipRepository
				.findFirstByUserIdAndOrganizationIdAndLeftAtIsNullOrderByJoinedAtDesc(user.getId(), organization.getId())
				.ifPresent(membership -> {
					membership.setLeftAt(Instant.now());
					membership.setExitReason(reason);
					teamMembershipRepository.save(membership);
				});
	}

	/** Every startup the person was in, newest first. Anyone else than an admin or their coordinator gets a 404. */
	@Transactional(readOnly = true)
	public TeamHistoryResponse history(UUID actorId, UUID userId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		AppUser person = domainLookupService.requireUser(userId);
		List<TeamMembership> stays = teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(userId);
		if (actor.getRole() != UserRole.ADMIN && !coordinatesPerson(actor, person, stays)) {
			throw new ResourceNotFoundException("User not found: " + userId);
		}
		return TeamHistoryResponse.of(person, stays);
	}

	private boolean coordinatesPerson(AppUser actor, AppUser person, List<TeamMembership> stays) {
		if (actor.getRole() != UserRole.COORDINATOR) {
			return false;
		}
		Set<UUID> programIds = new HashSet<>();
		if (person.getProgramId() != null) {
			programIds.add(person.getProgramId());
		}
		for (TeamMembership stay : stays) {
			programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(stay.getOrganization().getId()).stream()
					.map(ProgramStartup::getProgramId)
					.forEach(programIds::add);
		}
		return programRepository.findAllById(programIds).stream()
				.anyMatch((Program program) -> ProgramCoordination.coordinates(program, actor.getId(),
						programCoordinatorRepository));
	}
}
