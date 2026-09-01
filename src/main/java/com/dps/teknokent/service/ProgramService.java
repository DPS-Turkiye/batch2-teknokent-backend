package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.OrganizationResponse;
import com.dps.teknokent.dto.responses.ProgramInvitationResponse;
import com.dps.teknokent.dto.responses.ProgramMentorResponse;
import com.dps.teknokent.dto.responses.ProgramResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProgramService {

	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final DomainLookupService domainLookupService;

	@Transactional
	public ProgramResponse create(UUID actorId, String name) {
		AppUser actor = requireCoordinator(actorId);
		String trimmed = TextUtils.trim(name);
		if (trimmed == null || trimmed.isBlank()) {
			throw new InvalidRequestException("A program name is required.");
		}
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName(trimmed);
		program.setCreatedBy(actor.getId());
		program.setCreatedAt(Instant.now());
		return toResponse(programRepository.save(program));
	}

	@Transactional(readOnly = true)
	public List<ProgramResponse> list(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		List<Program> programs;
		if (actor.getRole() == UserRole.COORDINATOR) {
			programs = programRepository.findByCreatedByOrderByCreatedAtDesc(actor.getId());
		} else if (actor.getRole() == UserRole.MENTOR) {
			programs = programRepository.findAllByOrderByCreatedAtDesc();
		} else {
			throw new InvalidRequestException("Only a coordinator or mentor can view programs.");
		}
		return programs.stream().map(this::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public List<ProgramInvitationResponse> listInvitations(UUID actorId) {
		AppUser actor = requireMentor(actorId);
		return programMentorRepository
				.findByMentorIdAndStatusAndInvitedByOrderByCreatedAtDesc(
						actor.getId(),
						ProgramMentorStatus.PENDING,
						ProgramMentorInvitedBy.COORDINATOR)
				.stream()
				.map(this::toInvitation)
				.toList();
	}

	@Transactional
	public ProgramResponse addStartup(UUID actorId, UUID programId, UUID organizationId) {
		Program program = requireOwnedProgram(actorId, programId);
		domainLookupService.requireOrganization(organizationId);
		if (programStartupRepository.findByProgramIdAndOrganizationId(program.getId(), organizationId).isPresent()) {
			throw new ConflictException("This startup is already in the program.");
		}
		ProgramStartup row = new ProgramStartup();
		row.setId(UUID.randomUUID());
		row.setProgramId(program.getId());
		row.setOrganizationId(organizationId);
		row.setCreatedAt(Instant.now());
		programStartupRepository.save(row);
		return toResponse(program);
	}

	@Transactional
	public ProgramResponse removeStartup(UUID actorId, UUID programId, UUID organizationId) {
		Program program = requireOwnedProgram(actorId, programId);
		ProgramStartup row = programStartupRepository
				.findByProgramIdAndOrganizationId(program.getId(), organizationId)
				.orElseThrow(() -> new ResourceNotFoundException("Startup is not in this program."));
		programStartupRepository.delete(row);
		return toResponse(program);
	}

	@Transactional
	public ProgramResponse inviteMentor(UUID actorId, UUID programId, UUID mentorId) {
		Program program = requireOwnedProgram(actorId, programId);
		AppUser mentor = requireApprovedMentor(mentorId);
		upsertPending(program.getId(), mentor.getId(), ProgramMentorInvitedBy.COORDINATOR);
		return toResponse(program);
	}

	@Transactional
	public ProgramResponse requestToJoin(UUID actorId, UUID programId) {
		AppUser mentor = requireMentor(actorId);
		Program program = requireProgram(programId);
		upsertPending(program.getId(), mentor.getId(), ProgramMentorInvitedBy.MENTOR);
		return toResponse(program);
	}

	@Transactional
	public ProgramInvitationResponse decideInvitation(UUID actorId, UUID programId, boolean accepted) {
		AppUser mentor = requireMentor(actorId);
		ProgramMentor membership = programMentorRepository
				.findByProgramIdAndMentorId(programId, mentor.getId())
				.orElseThrow(() -> new ResourceNotFoundException("Program invitation not found."));
		if (membership.getStatus() != ProgramMentorStatus.PENDING
				|| membership.getInvitedBy() != ProgramMentorInvitedBy.COORDINATOR) {
			throw new InvalidRequestException("This invitation cannot be decided.");
		}
		applyDecision(membership, accepted);
		return toInvitation(membership);
	}

	@Transactional
	public ProgramResponse decideMentorRequest(UUID actorId, UUID programId, UUID mentorId, boolean accepted) {
		Program program = requireOwnedProgram(actorId, programId);
		ProgramMentor membership = programMentorRepository
				.findByProgramIdAndMentorId(program.getId(), mentorId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentor request not found."));
		if (membership.getStatus() != ProgramMentorStatus.PENDING
				|| membership.getInvitedBy() != ProgramMentorInvitedBy.MENTOR) {
			throw new InvalidRequestException("This mentor request cannot be decided.");
		}
		applyDecision(membership, accepted);
		return toResponse(program);
	}

	private void upsertPending(UUID programId, UUID mentorId, ProgramMentorInvitedBy invitedBy) {
		programMentorRepository.findByProgramIdAndMentorId(programId, mentorId).ifPresentOrElse(existing -> {
			if (existing.getStatus() == ProgramMentorStatus.ACCEPTED) {
				throw new ConflictException("This mentor is already in the program.");
			}
			if (existing.getStatus() == ProgramMentorStatus.PENDING) {
				throw new ConflictException("A pending request already exists for this mentor.");
			}
			existing.setStatus(ProgramMentorStatus.PENDING);
			existing.setInvitedBy(invitedBy);
			existing.setCreatedAt(Instant.now());
			existing.setDecidedAt(null);
			programMentorRepository.save(existing);
		}, () -> {
			ProgramMentor membership = new ProgramMentor();
			membership.setId(UUID.randomUUID());
			membership.setProgramId(programId);
			membership.setMentorId(mentorId);
			membership.setStatus(ProgramMentorStatus.PENDING);
			membership.setInvitedBy(invitedBy);
			membership.setCreatedAt(Instant.now());
			programMentorRepository.save(membership);
		});
	}

	private void applyDecision(ProgramMentor membership, boolean accepted) {
		membership.setStatus(accepted ? ProgramMentorStatus.ACCEPTED : ProgramMentorStatus.REJECTED);
		membership.setDecidedAt(Instant.now());
		programMentorRepository.save(membership);
	}

	private AppUser requireCoordinator(UUID actorId) {
		AppUser actor = requireActor(actorId);
		if (actor.getRole() != UserRole.COORDINATOR) {
			throw new InvalidRequestException("Only a coordinator can manage programs.");
		}
		return actor;
	}

	private AppUser requireMentor(UUID actorId) {
		AppUser actor = requireActor(actorId);
		if (actor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("Only a mentor can do this.");
		}
		return actor;
	}

	private AppUser requireApprovedMentor(UUID mentorId) {
		AppUser mentor = domainLookupService.requireUser(mentorId);
		if (mentor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("The selected user is not a mentor.");
		}
		if (mentor.getStatus() != UserAccountStatus.APPROVED) {
			throw new InvalidRequestException("Only an approved mentor can join a program.");
		}
		return mentor;
	}

	private AppUser requireActor(UUID actorId) {
		if (actorId == null) {
			throw new InvalidRequestException("A signed-in user is required.");
		}
		return domainLookupService.requireUser(actorId);
	}

	private Program requireProgram(UUID programId) {
		return programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
	}

	private Program requireOwnedProgram(UUID actorId, UUID programId) {
		AppUser actor = requireCoordinator(actorId);
		Program program = requireProgram(programId);
		if (!program.getCreatedBy().equals(actor.getId())) {
			throw new InvalidRequestException("Only the program coordinator can change this program.");
		}
		return program;
	}

	private ProgramResponse toResponse(Program program) {
		AppUser coordinator = domainLookupService.requireUser(program.getCreatedBy());
		List<OrganizationResponse> startups = programStartupRepository
				.findByProgramIdOrderByCreatedAtAsc(program.getId())
				.stream()
				.map(row -> OrganizationResponse.fromEntity(
						domainLookupService.requireOrganization(row.getOrganizationId())))
				.toList();
		List<ProgramMentorResponse> mentors = programMentorRepository
				.findByProgramIdOrderByCreatedAtAsc(program.getId())
				.stream()
				.map(this::toMentorResponse)
				.toList();
		return new ProgramResponse(
				program.getId(),
				program.getName(),
				coordinator.getId(),
				coordinator.getFullName(),
				program.getCreatedAt(),
				startups,
				mentors);
	}

	private ProgramMentorResponse toMentorResponse(ProgramMentor membership) {
		AppUser mentor = domainLookupService.requireUser(membership.getMentorId());
		return new ProgramMentorResponse(
				mentor.getId(),
				mentor.getFullName(),
				mentor.getEmail(),
				membership.getStatus(),
				membership.getInvitedBy(),
				membership.getCreatedAt());
	}

	private ProgramInvitationResponse toInvitation(ProgramMentor membership) {
		Program program = requireProgram(membership.getProgramId());
		AppUser coordinator = domainLookupService.requireUser(program.getCreatedBy());
		return new ProgramInvitationResponse(
				program.getId(),
				program.getName(),
				coordinator.getId(),
				coordinator.getFullName(),
				membership.getCreatedAt());
	}
}
