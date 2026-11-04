package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.TeamChangeKind;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.dto.responses.TeamChangeResponse;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamChangeRequest;
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

/**
 * Leaving a team. A member asks to leave and the leader asks to remove a member; both wait for the program's
 * coordinator. The leader leaves at once, handing the team to a successor, or archiving the startup when nobody
 * else is in it. Whoever leaves is back to "no startup" in the same program, and their open tasks are handed over.
 */
@Service
@RequiredArgsConstructor
public class TeamChangeService {

	private final DomainLookupService domainLookupService;
	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final TeamChangeRequestRepository teamChangeRequestRepository;
	private final TeamMembershipService teamMembershipService;
	private final TaskHandoverService taskHandoverService;
	private final NotificationService notificationService;

	/** The startup's waiting requests, for its members' Takım page. */
	@Transactional(readOnly = true)
	public List<TeamChangeResponse> pending(UUID actorId, UUID organizationId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		requireMember(organization, actorId);
		return teamChangeRequestRepository
				.findByOrganizationIdAndStatusOrderByCreatedAtAsc(organizationId, TeamChangeStatus.PENDING).stream()
				.map(TeamChangeResponse::of)
				.toList();
	}

	@Transactional
	public TeamChangeResponse requestLeave(UUID actorId, UUID organizationId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser member = requireMember(organization, actorId);
		if (isLeader(organization, member)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamLeaderLeavesWithSuccessor"));
		}
		return open(organization, member, member, TeamChangeKind.LEAVE);
	}

	@Transactional
	public TeamChangeResponse requestRemoval(UUID actorId, UUID organizationId, UUID memberId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser leader = requireLeader(organization, actorId);
		AppUser member = requireMember(organization, memberId);
		if (isLeader(organization, member)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamLeaderCannotBeRemoved"));
		}
		TeamChangeResponse opened = open(organization, member, leader, TeamChangeKind.REMOVE);
		notificationService.create(member, Texts.get(member, "notification.teamRemovalRequestedMember.title"),
				Texts.get(member, "notification.teamRemovalRequestedMember.body", leader.getFullName(),
						organization.getName()),
				"/team");
		return opened;
	}

	/** The requester withdraws their own waiting request. */
	@Transactional
	public TeamChangeResponse cancel(UUID actorId, UUID requestId) {
		TeamChangeRequest request = teamChangeRequestRepository.findById(requestId)
				.filter(row -> row.getRequestedBy().getId().equals(actorId))
				.orElseThrow(() -> new ResourceNotFoundException("Team change request not found: " + requestId));
		requirePending(request);
		close(request, TeamChangeStatus.CANCELLED, null);
		return TeamChangeResponse.of(request);
	}

	/**
	 * The leader leaves at once, after the warning on the page: the successor leads from now on. Without anyone
	 * else in the team the startup is archived instead, and its code stops working.
	 */
	@Transactional
	public UserResponse leaveAsLeader(UUID actorId, UUID organizationId, UUID successorId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser leader = requireLeader(organization, actorId);
		List<AppUser> others = appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId).stream()
				.filter(member -> !member.getId().equals(leader.getId()))
				.toList();
		Optional<UUID> programId = programOf(organization);

		if (others.isEmpty()) {
			organization.setArchivedAt(Instant.now());
			organization.setInviteCode(null);
			organizationRepository.save(organization);
			depart(leader, organization, TeamExitReason.LEFT);
			programId.ifPresent(id -> notifyCoordinators(id, "notification.teamArchived", organization.getName(),
					leader.getFullName()));
			return UserResponse.fromEntity(leader);
		}

		AppUser successor = others.stream()
				.filter(member -> member.getId().equals(successorId))
				.findFirst()
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.teamSuccessorRequired")));
		if (teamChangeRequestRepository.existsByMemberIdAndOrganizationIdAndStatus(successor.getId(),
				organizationId, TeamChangeStatus.PENDING)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamSuccessorLeaving"));
		}
		organization.setFounder(successor);
		organizationRepository.save(organization);
		depart(leader, organization, TeamExitReason.LEFT);

		notificationService.create(successor, Texts.get(successor, "notification.teamLeaderChanged.title"),
				Texts.get(successor, "notification.teamLeaderChanged.body", leader.getFullName(),
						organization.getName()),
				"/team");
		programId.ifPresent(id -> notifyCoordinators(id, "notification.teamLeaderLeft", leader.getFullName(),
				organization.getName(), successor.getFullName()));
		return UserResponse.fromEntity(leader);
	}

	/** The coordinator's decision from "Onaylar"; the caller has already checked they run the program. */
	@Transactional
	public void decide(UUID actorId, UUID programId, UUID requestId, boolean accepted) {
		TeamChangeRequest request = teamChangeRequestRepository.findById(requestId)
				.filter(row -> row.getProgramId().equals(programId))
				.orElseThrow(() -> new ResourceNotFoundException("No team change " + requestId + " in this program"));
		requirePending(request);
		AppUser coordinator = domainLookupService.requireUser(actorId);
		Organization organization = request.getOrganization();
		AppUser member = request.getMember();

		if (!accepted) {
			close(request, TeamChangeStatus.REJECTED, coordinator);
			notifyRejected(request, request.getRequestedBy());
			if (request.getKind() == TeamChangeKind.REMOVE) {
				notifyRejected(request, member);
			}
			return;
		}
		if (!isMember(organization, member)) {
			close(request, TeamChangeStatus.CANCELLED, coordinator);
			return;
		}
		if (isLeader(organization, member)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamLeaderLeavesWithSuccessor"));
		}
		close(request, TeamChangeStatus.APPROVED, coordinator);
		boolean removed = request.getKind() == TeamChangeKind.REMOVE;
		depart(member, organization, removed ? TeamExitReason.REMOVED : TeamExitReason.LEFT);

		String key = removed ? "notification.teamRemoved" : "notification.teamLeft";
		notificationService.create(member, Texts.get(member, key + ".title"),
				Texts.get(member, key + ".body", organization.getName()), "/startup");
		AppUser leader = organization.getFounder();
		if (leader != null) {
			String leaderKey = removed ? "notification.teamMemberRemoved" : "notification.teamMemberLeft";
			notificationService.create(leader, Texts.get(leader, leaderKey + ".title", member.getFullName()),
					Texts.get(leader, leaderKey + ".body", member.getFullName(), organization.getName()), "/team");
		}
	}

	/** A startup outside any program has no coordinator to ask: its leader removes a member at once. */
	@Transactional
	public void removeOutsideProgram(UUID actorId, UUID organizationId, UUID memberId) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		requireLeader(organization, actorId);
		AppUser member = requireMember(organization, memberId);
		if (isLeader(organization, member)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamLeaderCannotBeRemoved"));
		}
		if (programOf(organization).isPresent()) {
			throw new InvalidRequestException(Texts.forRequest("error.teamRemovalNeedsCoordinator"));
		}
		depart(member, organization, TeamExitReason.REMOVED);
	}

	private TeamChangeResponse open(Organization organization, AppUser member, AppUser requester,
			TeamChangeKind kind) {
		if (teamChangeRequestRepository.existsByMemberIdAndOrganizationIdAndStatus(member.getId(),
				organization.getId(), TeamChangeStatus.PENDING)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamChangePending"));
		}
		UUID programId = programOf(organization)
				.orElseThrow(() -> new InvalidRequestException(Texts.forRequest("error.teamChangeNoProgram")));
		TeamChangeRequest request = new TeamChangeRequest();
		request.setOrganization(organization);
		request.setProgramId(programId);
		request.setMember(member);
		request.setRequestedBy(requester);
		request.setKind(kind);
		teamChangeRequestRepository.saveAndFlush(request);

		if (kind == TeamChangeKind.LEAVE) {
			notifyCoordinators(programId, "notification.teamLeaveRequested", member.getFullName(),
					organization.getName());
		} else {
			notifyCoordinators(programId, "notification.teamRemovalRequested", requester.getFullName(),
					member.getFullName(), organization.getName());
		}
		return TeamChangeResponse.of(request);
	}

	/** Out of the team, back to "no startup" in the same program, with the open tasks handed over. */
	private void depart(AppUser member, Organization organization, TeamExitReason reason) {
		teamMembershipService.left(member, organization, reason);
		if (member.getProgramId() == null) {
			programOf(organization).ifPresent(member::setProgramId);
		}
		member.setOrganization(null);
		appUserRepository.save(member);
		teamChangeRequestRepository
				.findByOrganizationIdAndStatusOrderByCreatedAtAsc(organization.getId(), TeamChangeStatus.PENDING)
				.stream()
				.filter(other -> other.getMember().getId().equals(member.getId()))
				.forEach(other -> close(other, TeamChangeStatus.CANCELLED, null));
		taskHandoverService.markLeft(member, organization);
	}

	private void close(TeamChangeRequest request, TeamChangeStatus status, AppUser decidedBy) {
		request.setStatus(status);
		request.setDecidedBy(decidedBy);
		request.setDecidedAt(Instant.now());
		teamChangeRequestRepository.save(request);
	}

	private void notifyRejected(TeamChangeRequest request, AppUser recipient) {
		notificationService.create(recipient, Texts.get(recipient, "notification.teamChangeRejected.title"),
				Texts.get(recipient, "notification.teamChangeRejected.body", request.getOrganization().getName()),
				"/team");
	}

	private void notifyCoordinators(UUID programId, String key, Object... args) {
		programRepository.findById(programId).ifPresent(program -> appUserRepository
				.findAllById(ProgramCoordination.coordinatorIds(program, programCoordinatorRepository))
				.forEach(coordinator -> notificationService.create(coordinator,
						Texts.get(coordinator, key + ".title"), Texts.get(coordinator, key + ".body", args),
						"/coordinator/programs/" + programId + "/approvals")));
	}

	/** The program the startup is in: the one it is active in, else the first it joined. */
	private Optional<UUID> programOf(Organization organization) {
		if (organization.getActiveProgramId() != null) {
			return Optional.of(organization.getActiveProgramId());
		}
		return programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(organization.getId()).stream()
				.map(ProgramStartup::getProgramId)
				.findFirst();
	}

	private static void requirePending(TeamChangeRequest request) {
		if (request.getStatus() != TeamChangeStatus.PENDING) {
			throw new InvalidRequestException(Texts.forRequest("error.teamChangeNotPending"));
		}
	}

	private AppUser requireMember(Organization organization, UUID userId) {
		AppUser user = domainLookupService.requireUser(userId);
		if (!isMember(organization, user)) {
			throw new ResourceNotFoundException("Team member not found: " + userId);
		}
		return user;
	}

	private AppUser requireLeader(Organization organization, UUID userId) {
		AppUser user = requireMember(organization, userId);
		if (!isLeader(organization, user)) {
			throw new InvalidRequestException(Texts.forRequest("error.teamLeaderOnly"));
		}
		return user;
	}

	private static boolean isMember(Organization organization, AppUser user) {
		return user.getOrganization() != null && organization.getId().equals(user.getOrganization().getId());
	}

	private static boolean isLeader(Organization organization, AppUser user) {
		return organization.getFounder() != null && organization.getFounder().getId().equals(user.getId());
	}
}
