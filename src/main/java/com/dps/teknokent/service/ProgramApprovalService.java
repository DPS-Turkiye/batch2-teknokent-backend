package com.dps.teknokent.service;

import com.dps.teknokent.domain.TeamChangeStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramApprovalsResponse;
import com.dps.teknokent.dto.responses.TeamChangeResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * A program's coordinator decides on the people who signed up into it and on startups created after signup. An
 * admin can still decide on people from the account list.
 */
@Service
@RequiredArgsConstructor
public class ProgramApprovalService {

	private final CoordinatorProgramService coordinatorProgramService;
	private final AppUserService appUserService;
	private final DomainLookupService domainLookupService;
	private final NotificationService notificationService;
	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final TeamChangeRequestRepository teamChangeRequestRepository;
	private final TeamChangeService teamChangeService;
	private final ProgramMentorRepository programMentorRepository;
	private final MentorPoolService mentorPoolService;

	@Transactional(readOnly = true)
	public ProgramApprovalsResponse approvals(UUID actorId, UUID programId) {
		coordinatorProgramService.requireManagedProgram(actorId, programId);
		return approvalsOf(programId);
	}

	/** "Takımı olmayanlar": approved people of the program who have not joined or created a startup yet. */
	@Transactional(readOnly = true)
	public List<ProgramApprovalsResponse.Person> withoutTeam(UUID actorId, UUID programId) {
		coordinatorProgramService.requireManagedProgram(actorId, programId);
		return appUserRepository.findByProgramIdAndPermissionAndStatusAndOrganizationIsNullOrderByFullNameAsc(
				programId, UserRole.MENTEE, UserAccountStatus.APPROVED).stream()
				.map(ProgramApprovalsResponse.Person::of)
				.toList();
	}

	@Transactional
	public ProgramApprovalsResponse decidePerson(UUID actorId, UUID programId, UUID userId, boolean accepted) {
		coordinatorProgramService.requireManagedProgram(actorId, programId);
		AppUser person = domainLookupService.requireUser(userId);
		if (!person.has(UserRole.MENTEE) || !programId.equals(person.getProgramId())) {
			throw new ResourceNotFoundException("No applicant " + userId + " in this program");
		}
		appUserService.decide(person, accepted ? UserAccountStatus.APPROVED : UserAccountStatus.REJECTED);
		return approvalsOf(programId);
	}

	/** A rejected startup stays with its members; its founder can fix the profile and send it again. */
	@Transactional
	public ProgramApprovalsResponse decideStartup(UUID actorId, UUID programId, UUID organizationId,
			boolean accepted) {
		coordinatorProgramService.requireManagedProgram(actorId, programId);
		Organization startup = domainLookupService.requireOrganization(organizationId);
		if (programStartupRepository.findByProgramIdAndOrganizationId(programId, organizationId).isEmpty()) {
			throw new ResourceNotFoundException("No startup " + organizationId + " in this program");
		}
		if (startup.getApprovalStatus() != UserAccountStatus.PENDING) {
			throw new InvalidRequestException("Only a startup waiting for approval can be decided");
		}
		startup.setApprovalStatus(accepted ? UserAccountStatus.APPROVED : UserAccountStatus.REJECTED);
		organizationRepository.save(startup);

		AppUser founder = startup.getFounder();
		if (founder != null) {
			String key = accepted ? "notification.startupApproved" : "notification.startupRejected";
			notificationService.create(founder, Texts.get(founder, key + ".title"),
					Texts.get(founder, key + ".body", startup.getName()), "/startup");
		}
		return approvalsOf(programId);
	}

	/** Same decision as the Mentor Havuzu's, so it also settles the account of a mentor still signing up. */
	@Transactional
	public ProgramApprovalsResponse decideMentor(UUID actorId, UUID programId, UUID mentorId, boolean accepted) {
		mentorPoolService.decide(actorId, programId, mentorId, accepted);
		return approvalsOf(programId);
	}

	@Transactional
	public ProgramApprovalsResponse decideTeamChange(UUID actorId, UUID programId, UUID requestId, boolean accepted) {
		coordinatorProgramService.requireManagedProgram(actorId, programId);
		teamChangeService.decide(actorId, programId, requestId, accepted);
		return approvalsOf(programId);
	}

	/** Lists what CoordinatorProgramService#pendingApprovalCount counts; change the two together. */
	private ProgramApprovalsResponse approvalsOf(UUID programId) {
		List<ProgramApprovalsResponse.Person> people = appUserRepository
				.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(programId, UserRole.MENTEE,
						UserAccountStatus.PENDING)
				.stream()
				.map(ProgramApprovalsResponse.Person::of)
				.toList();
		List<UUID> mentorIds = programMentorRepository.findByProgramIdOrderByCreatedAtAsc(programId).stream()
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.PENDING
						&& membership.getInvitedBy() == ProgramMentorInvitedBy.MENTOR)
				.map(ProgramMentor::getMentorId)
				.toList();
		List<ProgramApprovalsResponse.Person> mentors = mentorIds.isEmpty() ? List.of()
				: appUserRepository.findAllById(mentorIds).stream()
						.map(ProgramApprovalsResponse.Person::of)
						.toList();
		Set<UUID> organizationIds = programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId).stream()
				.map(ProgramStartup::getOrganizationId)
				.collect(Collectors.toSet());
		List<ProgramApprovalsResponse.Startup> startups = organizationIds.isEmpty()
				? List.of()
				: organizationRepository
						.findByIdInAndApprovalStatusOrderByCreatedAtAsc(organizationIds, UserAccountStatus.PENDING)
						.stream()
						.map(ProgramApprovalsResponse.Startup::of)
						.toList();
		List<TeamChangeResponse> teamChanges = teamChangeRequestRepository
				.findByProgramIdAndStatusOrderByCreatedAtAsc(programId, TeamChangeStatus.PENDING).stream()
				.map(TeamChangeResponse::of)
				.toList();
		return new ProgramApprovalsResponse(people, mentors, startups, teamChanges);
	}
}
