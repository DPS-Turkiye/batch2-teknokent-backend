package com.dps.teknokent.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * The admin puts a mentor or a startup into a program from the admin console. The admin's word is final: the
 * membership counts at once, anything still waiting for approval is approved with it, and the program's
 * coordinators are told.
 */
@Service
@RequiredArgsConstructor
public class AdminProgramMembershipService {

	private final DomainLookupService domainLookupService;
	private final AppUserService appUserService;
	private final NotificationService notificationService;
	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramEligibilityService programEligibilityService;

	@Transactional
	public void assignMentor(UUID actorId, UUID programId, UUID mentorId) {
		requireAdmin(actorId);
		Program program = requireActiveProgram(programId);
		AppUser mentor = domainLookupService.requireUser(mentorId);
		if (mentor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("The selected user is not a mentor.");
		}
		programEligibilityService.requirePerson(program, mentor.getEmail());
		ProgramMentor membership = programMentorRepository.findByProgramIdAndMentorId(programId, mentorId)
				.orElseGet(() -> newMembership(programId, mentorId));
		if (membership.getStatus() == ProgramMentorStatus.ACCEPTED) {
			throw new ConflictException("This mentor is already in the program.");
		}
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		membership.setDecidedAt(Instant.now());
		programMentorRepository.save(membership);
		if (mentor.getStatus() == UserAccountStatus.PENDING) {
			appUserService.decide(mentor, UserAccountStatus.APPROVED);
		}
		notificationService.create(mentor, Texts.get(mentor, "notification.programAssigned.title"),
				Texts.get(mentor, "notification.programAssigned.body", program.getName()), "/mentor-dashboard");
		notifyCoordinators(program, mentor.getFullName());
	}

	@Transactional
	public void assignStartup(UUID actorId, UUID programId, UUID organizationId) {
		requireAdmin(actorId);
		Program program = requireActiveProgram(programId);
		Organization startup = domainLookupService.requireOrganization(organizationId);
		if (programStartupRepository.findByProgramIdAndOrganizationId(programId, organizationId).isPresent()) {
			throw new ConflictException("This startup is already in the program.");
		}
		programEligibilityService.requireTeam(program, organizationId);
		ProgramStartup row = new ProgramStartup();
		row.setId(UUID.randomUUID());
		row.setProgramId(programId);
		row.setOrganizationId(organizationId);
		row.setCreatedAt(Instant.now());
		programStartupRepository.save(row);

		if (startup.getActiveProgramId() == null) {
			startup.setActiveProgramId(programId);
		}
		if (startup.getApprovalStatus() == UserAccountStatus.PENDING) {
			startup.setApprovalStatus(UserAccountStatus.APPROVED);
		}
		organizationRepository.save(startup);

		AppUser founder = startup.getFounder();
		if (founder != null) {
			if (founder.getStatus() == UserAccountStatus.PENDING) {
				appUserService.decide(founder, UserAccountStatus.APPROVED);
			}
			notificationService.create(founder, Texts.get(founder, "notification.startupProgramAssigned.title"),
					Texts.get(founder, "notification.startupProgramAssigned.body", startup.getName(), program.getName()),
					"/startup");
		}
		notifyCoordinators(program, startup.getName());
	}

	private void notifyCoordinators(Program program, String memberName) {
		ProgramCoordination.runners(program, programCoordinatorRepository, appUserRepository)
				.forEach(coordinator -> notificationService.create(coordinator,
						Texts.get(coordinator, "notification.adminAddedToProgram.title"),
						Texts.get(coordinator, "notification.adminAddedToProgram.body", memberName, program.getName()),
						"/coordinator/programs/" + program.getId()));
	}

	private static ProgramMentor newMembership(UUID programId, UUID mentorId) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(programId);
		membership.setMentorId(mentorId);
		membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		membership.setCreatedAt(Instant.now());
		return membership;
	}

	private Program requireActiveProgram(UUID programId) {
		Program program = programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
		if (program.getStatus() != ProgramStatus.ACTIVE) {
			throw new InvalidRequestException(Texts.forRequest("error.programNotOpen"));
		}
		return program;
	}

	private void requireAdmin(UUID actorId) {
		if (domainLookupService.requireUser(actorId).getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only an admin can assign people to programs.");
		}
	}
}
