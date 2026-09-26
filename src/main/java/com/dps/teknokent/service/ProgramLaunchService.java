package com.dps.teknokent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.TeamJoinStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateCoordinatorProgramRequest;
import com.dps.teknokent.dto.requests.TeamInviteInput;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.dto.responses.ProgramTeamResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramTeamInvite;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramTeamInviteRepository;
import com.dps.teknokent.util.TeamCodes;

import lombok.RequiredArgsConstructor;

/**
 * Flow 03.3: a coordinator launches a program with its rules and teams, and follows which team leads have
 * joined with their code. Codes are copied and shared by the coordinator; nothing here sends email.
 */
@Service
@RequiredArgsConstructor
public class ProgramLaunchService {

	private static final int CODE_ATTEMPTS = 20;

	private final CoordinatorProgramService programService;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramTeamInviteRepository inviteRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final OrganizationRepository organizationRepository;
	private final AppUserRepository appUserRepository;

	@Transactional
	public CoordinatorProgramResponse.Detail create(UUID actorId, CreateCoordinatorProgramRequest request) {
		AppUser actor = programService.requireCoordinatorOrAdmin(actorId);
		if (request.startDate() != null && request.endDate() != null && request.endDate().isBefore(request.startDate())) {
			throw new InvalidRequestException("The program cannot end before it starts.");
		}
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName(request.name().trim());
		program.setDescription(request.description() == null || request.description().isBlank()
				? null
				: request.description().trim());
		program.setStartDate(request.startDate());
		program.setEndDate(request.endDate());
		program.setCreatedBy(actor.getId());
		program.setCreatedAt(Instant.now());
		program.setDefaultMentorCapacity(request.defaultMentorCapacity());
		program.setMeetingFrequencyDays(request.meetingFrequencyDays());
		program.setStaleAfterDays(request.communicationGapDays());
		program.setCommunicationGapDays(request.communicationGapDays());
		program.setBottleneckFlagDays(request.bottleneckFlagDays());
		programRepository.save(program);

		for (UUID coordinatorId : new LinkedHashSet<>(request.coordinatorIds())) {
			if (coordinatorId.equals(actor.getId())) {
				continue;
			}
			AppUser coordinator = appUserRepository.findById(coordinatorId)
					.filter(user -> user.getRole() == UserRole.COORDINATOR
							&& user.getStatus() == UserAccountStatus.APPROVED)
					.orElseThrow(() -> new InvalidRequestException("Only approved coordinators can run a program."));
			ProgramCoordinator row = new ProgramCoordinator();
			row.setId(UUID.randomUUID());
			row.setProgramId(program.getId());
			row.setUserId(coordinator.getId());
			row.setAddedBy(actor.getId());
			row.setCreatedAt(Instant.now());
			programCoordinatorRepository.save(row);
		}
		invite(program, request.teams(), actor.getId());
		return programService.detail(actorId, program.getId());
	}

	@Transactional(readOnly = true)
	public List<ProgramTeamResponse> teams(UUID actorId, UUID programId) {
		programService.requireManagedProgram(actorId, programId);
		return rows(programId);
	}

	@Transactional
	public List<ProgramTeamResponse> addTeams(UUID actorId, UUID programId, List<TeamInviteInput> teams) {
		Program program = programService.requireManagedProgram(actorId, programId);
		invite(program, teams, actorId);
		return rows(programId);
	}

	/** Fixing a mistyped email or name is only possible until the lead has joined. */
	@Transactional
	public List<ProgramTeamResponse> updateTeam(UUID actorId, UUID programId, UUID inviteId, TeamInviteInput input) {
		programService.requireManagedProgram(actorId, programId);
		ProgramTeamInvite invite = requireWaitingInvite(programId, inviteId);
		String email = normalizeEmail(input.leadEmail());
		if (!email.equalsIgnoreCase(invite.getLeadEmail())
				&& inviteRepository.existsByProgramIdAndLeadEmailIgnoreCase(programId, email)) {
			throw new InvalidRequestException("Another team in this program already has this lead email.");
		}
		invite.setTeamName(input.teamName().trim());
		invite.setLeadEmail(email);
		inviteRepository.save(invite);
		return rows(programId);
	}

	@Transactional
	public List<ProgramTeamResponse> removeTeam(UUID actorId, UUID programId, UUID inviteId) {
		programService.requireManagedProgram(actorId, programId);
		inviteRepository.delete(requireWaitingInvite(programId, inviteId));
		return rows(programId);
	}

	private void invite(Program program, List<TeamInviteInput> teams, UUID actorId) {
		Set<String> emails = new HashSet<>();
		String prefix = TeamCodes.prefix(program.getName());
		for (TeamInviteInput team : teams) {
			String email = normalizeEmail(team.leadEmail());
			if (!emails.add(email) || inviteRepository.existsByProgramIdAndLeadEmailIgnoreCase(program.getId(), email)) {
				throw new InvalidRequestException("Each team needs its own lead email: " + email);
			}
			ProgramTeamInvite invite = new ProgramTeamInvite();
			invite.setId(UUID.randomUUID());
			invite.setProgramId(program.getId());
			invite.setTeamName(team.teamName().trim());
			invite.setLeadEmail(email);
			invite.setCode(freshCode(prefix));
			invite.setCreatedBy(actorId);
			invite.setCreatedAt(Instant.now());
			inviteRepository.save(invite);
		}
	}

	/** Unique against other team codes and against the invite codes organizations already hand to members. */
	private String freshCode(String prefix) {
		for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
			String code = TeamCodes.next(prefix);
			if (!inviteRepository.existsByCode(code) && !organizationRepository.existsByInviteCode(code)) {
				return code;
			}
		}
		throw new IllegalStateException("Could not find a free team code for prefix " + prefix);
	}

	private List<ProgramTeamResponse> rows(UUID programId) {
		List<ProgramTeamInvite> invites = inviteRepository.findByProgramIdOrderByCreatedAtAsc(programId);
		List<ProgramStartup> startups = programStartupRepository.findByProgramIdIn(List.of(programId));
		Set<UUID> organizationIds = startups.stream().map(ProgramStartup::getOrganizationId).collect(Collectors.toSet());
		Map<UUID, Organization> organizations = organizationRepository.findAllById(organizationIds).stream()
				.collect(Collectors.toMap(Organization::getId, Function.identity()));

		List<ProgramTeamResponse> rows = new ArrayList<>();
		Set<UUID> covered = new HashSet<>();
		for (ProgramTeamInvite invite : invites) {
			Organization organization = invite.getOrganizationId() == null ? null : organizations.get(invite.getOrganizationId());
			if (organization != null) {
				covered.add(organization.getId());
			}
			rows.add(new ProgramTeamResponse(
					invite.getId(),
					invite.getOrganizationId(),
					organization == null ? invite.getTeamName() : organization.getName(),
					invite.getOrganizationId() == null ? TeamJoinStatus.WAITING : TeamJoinStatus.JOINED,
					organization == null || organization.getFounder() == null ? null : organization.getFounder().getFullName(),
					invite.getLeadEmail(),
					invite.getCode(),
					invite.getCreatedAt(),
					invite.getJoinedAt(),
					organization == null ? 0 : memberCount(organization)));
		}
		for (ProgramStartup startup : startups) {
			Organization organization = organizations.get(startup.getOrganizationId());
			if (organization == null || covered.contains(organization.getId())) {
				continue;
			}
			AppUser founder = organization.getFounder();
			rows.add(new ProgramTeamResponse(
					null,
					organization.getId(),
					organization.getName(),
					TeamJoinStatus.JOINED,
					founder == null ? null : founder.getFullName(),
					founder == null ? null : founder.getEmail(),
					organization.getInviteCode(),
					startup.getCreatedAt(),
					startup.getCreatedAt(),
					memberCount(organization)));
		}
		return rows;
	}

	private int memberCount(Organization organization) {
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()).size();
	}

	private ProgramTeamInvite requireWaitingInvite(UUID programId, UUID inviteId) {
		ProgramTeamInvite invite = inviteRepository.findByIdAndProgramId(inviteId, programId)
				.orElseThrow(() -> new ResourceNotFoundException("Team invite not found: " + inviteId));
		if (invite.getOrganizationId() != null) {
			throw new InvalidRequestException("This team's lead has already joined.");
		}
		return invite;
	}

	private static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}
}
