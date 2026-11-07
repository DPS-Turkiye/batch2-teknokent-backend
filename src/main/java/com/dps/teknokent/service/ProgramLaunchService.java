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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.TeamJoinStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateCoordinatorProgramRequest;
import com.dps.teknokent.dto.requests.TeamInviteInput;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.dto.responses.ProgramTeamDetailResponse;
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
import com.dps.teknokent.util.ProgramDrafts;
import com.dps.teknokent.util.TeamCodes;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Flow 03.3: an admin creates a program as a draft with its coordinators; its coordinators add teams and follow
 * which team leads have joined with their code. The coordinator copies a code or emails it to the lead through SES.
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
	private final MailService mailService;
	private final CoordinatorMentorshipService mentorshipService;
	private final PlatformTransactionManager transactionManager;
	private final ProgramEligibilityService programEligibilityService;
	private final NotificationService notificationService;


	/** An admin creates every program as a draft (Taslak) with its coordinators; launching is a separate step. */
	@Transactional
	public CoordinatorProgramResponse.Detail create(UUID actorId, CreateCoordinatorProgramRequest request) {
		AppUser actor = programService.requireCoordinatorOrAdmin(actorId);
		if (actor.getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only an admin creates programs.");
		}
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
		program.setProgramType(request.type());
		program.setStatus(ProgramStatus.DRAFT);
		program.setIndividualMentees(request.type() == ProgramType.PEER_MENTORING);
		if (request.defaultMentorCapacity() != null) {
			program.setDefaultMentorCapacity(request.defaultMentorCapacity());
		}
		if (request.meetingFrequencyDays() != null) {
			program.setMeetingFrequencyDays(request.meetingFrequencyDays());
		}
		if (request.communicationGapDays() != null) {
			program.setStaleAfterDays(request.communicationGapDays());
			program.setCommunicationGapDays(request.communicationGapDays());
		}
		if (request.bottleneckFlagDays() != null) {
			program.setBottleneckFlagDays(request.bottleneckFlagDays());
		}
		programRepository.save(program);

		for (UUID coordinatorId : new LinkedHashSet<>(request.coordinatorIds())) {
			AppUser coordinator = appUserRepository.findById(coordinatorId)
					.filter(user -> user.has(UserRole.COORDINATOR)
							&& user.getStatus() == UserAccountStatus.APPROVED)
					.orElseThrow(() -> new InvalidRequestException("Only approved coordinators can run a program."));
			ProgramCoordinator row = new ProgramCoordinator();
			row.setId(UUID.randomUUID());
			row.setProgramId(program.getId());
			row.setUserId(coordinator.getId());
			row.setAddedBy(actor.getId());
			row.setCreatedAt(Instant.now());
			programCoordinatorRepository.save(row);
			notificationService.create(
					coordinator,
					Texts.get(coordinator, "notification.addedAsCoordinator.title"),
					Texts.get(coordinator, "notification.addedAsCoordinator.body", program.getName()),
					"/coordinator/programs/" + program.getId());
		}
		if (request.phases() != null) {
			programService.writePhases(program.getId(), request.phases());
		}
		programService.attachSyllabus(program, actor.getId(), request.syllabusDocumentId());
		if (request.teams() != null) {
			invite(program, request.teams(), actor.getId());
		}
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
		if (!email.equalsIgnoreCase(invite.getLeadEmail())) {
			invite.setCodeSentAt(null);
			invite.setCodeSendFailed(false);
		}
		invite.setTeamName(input.teamName().trim());
		invite.setLeadEmail(email);
		inviteRepository.save(invite);
		return rows(programId);
	}

	/**
	 * "Kodu gönder" / "Kodu tekrar gönder": emails the code to the lead, and marks the team "Sorunlu" if SES refuses.
	 * SES runs between two short transactions, so its round trip does not hold a pool connection.
	 */
	public List<ProgramTeamResponse> sendCode(UUID actorId, UUID programId, UUID inviteId) {
		TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
		readOnly.setReadOnly(true);
		CodeMail mail = readOnly.execute(status -> {
			Program program = programService.requireManagedProgram(actorId, programId);
			ProgramDrafts.requireLaunched(program);
			ProgramTeamInvite invite = requireWaitingInvite(programId, inviteId);
			if (!mailService.isConfigured()) {
				throw new InvalidRequestException(Texts.forRequest("error.mailDisabled"));
			}
			return new CodeMail(
					invite.getLeadEmail(),
					Texts.inBoth(" / ", "mail.teamCode.subject", program.getName()),
					Texts.inBoth(Texts.MAIL_LANGUAGE_BREAK, "mail.teamCode.body", program.getName(),
							invite.getTeamName(), invite.getCode(), mailService.link("/invite")));
		});
		boolean sent = mailService.sendNow(mail.to(), mail.subject(), mail.body());
		return new TransactionTemplate(transactionManager).execute(status -> {
			// A lead address edited while SES answered resets the status itself; don't stamp it with this send.
			inviteRepository.findByIdAndProgramId(inviteId, programId)
					.filter(invite -> mail.to().equals(invite.getLeadEmail()))
					.ifPresent(invite -> {
						invite.setCodeSendFailed(!sent);
						if (sent) {
							invite.setCodeSentAt(Instant.now());
						}
						inviteRepository.save(invite);
					});
			return rows(programId);
		});
	}

	private record CodeMail(String to, String subject, String body) {
	}

	/** The Takımlar drawer for a team that has joined the program. */
	@Transactional(readOnly = true)
	public ProgramTeamDetailResponse teamDetail(UUID actorId, UUID programId, UUID organizationId) {
		programService.requireManagedProgram(actorId, programId);
		boolean inProgram = programStartupRepository.findByProgramIdIn(List.of(programId)).stream()
				.anyMatch(startup -> organizationId.equals(startup.getOrganizationId()));
		Organization organization = organizationRepository.findById(organizationId)
				.filter(found -> inProgram)
				.orElseThrow(() -> new ResourceNotFoundException("Team not found in this program: " + organizationId));
		String code = inviteRepository.findByProgramIdOrderByCreatedAtAsc(programId).stream()
				.filter(invite -> organizationId.equals(invite.getOrganizationId()))
				.map(ProgramTeamInvite::getCode)
				.findFirst()
				.orElse(organization.getInviteCode());
		List<ProgramTeamDetailResponse.Person> members = appUserRepository
				.findByOrganizationIdOrderByFullNameAsc(organizationId).stream()
				.map(ProgramLaunchService::person)
				.toList();
		return new ProgramTeamDetailResponse(
				organization.getId(),
				organization.getName(),
				organization.getSector() == null ? null : organization.getSector().value(),
				organization.getFounder() == null ? null : person(organization.getFounder()),
				code,
				members,
				mentorshipService.list(actorId, programId).stream()
						.filter(row -> organizationId.equals(row.organizationId()))
						.toList());
	}

	private static ProgramTeamDetailResponse.Person person(AppUser user) {
		return new ProgramTeamDetailResponse.Person(
				user.getId(), user.getFullName(), user.getJobTitle(), user.getEmail(), user.getCreatedAt());
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
			programEligibilityService.requirePerson(program, email);
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
				.filter(Organization::isApprovedForProgram)
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
					organization == null ? 0 : memberCount(organization),
					invite.getCodeSentAt(),
					invite.isCodeSendFailed()));
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
					memberCount(organization),
					null,
					false));
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
