package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.RequiredArgsConstructor;

/** Who runs a program: its creator plus the coordinators added in Program Ayarları → Koordinatörler. */
@Service
@RequiredArgsConstructor
public class ProgramCoordinatorService {

	private final CoordinatorProgramService programService;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;

	@Transactional(readOnly = true)
	public List<CoordinatorProgramResponse.CoordinatorRef> list(UUID actorId, UUID programId) {
		return refs(programService.requireManagedProgram(actorId, programId));
	}

	/** Approved coordinators who could be added, i.e. not already running this program. */
	@Transactional(readOnly = true)
	public List<CoordinatorProgramResponse.CoordinatorRef> candidates(UUID actorId, UUID programId) {
		Program program = programService.requireManagedProgram(actorId, programId);
		Set<UUID> current = Set.copyOf(ProgramCoordination.coordinatorIds(program, programCoordinatorRepository));
		return appUserRepository.findByRoleAndStatusOrderByFullNameAsc(UserRole.COORDINATOR, UserAccountStatus.APPROVED)
				.stream()
				.filter(user -> !current.contains(user.getId()))
				.map(user -> new CoordinatorProgramResponse.CoordinatorRef(user.getId(), user.getFullName(), false))
				.toList();
	}

	@Transactional
	public List<CoordinatorProgramResponse.CoordinatorRef> add(UUID actorId, UUID programId, UUID userId) {
		Program program = programService.requireManagedProgram(actorId, programId);
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
		if (user.getRole() != UserRole.COORDINATOR || user.getStatus() != UserAccountStatus.APPROVED) {
			throw new InvalidRequestException("Only an approved coordinator can run a program.");
		}
		if (ProgramCoordination.coordinates(program, userId, programCoordinatorRepository)) {
			throw new InvalidRequestException("This coordinator already runs the program.");
		}
		ProgramCoordinator row = new ProgramCoordinator();
		row.setId(UUID.randomUUID());
		row.setProgramId(programId);
		row.setUserId(userId);
		row.setAddedBy(actorId);
		row.setCreatedAt(Instant.now());
		programCoordinatorRepository.save(row);
		notificationService.create(
				user,
				"Programa koordinatör olarak eklendiniz",
				program.getName() + " programını artık siz de yönetebilirsiniz.",
				"/coordinator/programs/" + programId);
		return refs(program);
	}

	/** The creator cannot be removed: the program is theirs, and removing them could leave it with nobody. */
	@Transactional
	public List<CoordinatorProgramResponse.CoordinatorRef> remove(UUID actorId, UUID programId, UUID userId) {
		Program program = programService.requireManagedProgram(actorId, programId);
		if (program.getCreatedBy().equals(userId)) {
			throw new InvalidRequestException("The coordinator who created the program cannot be removed.");
		}
		ProgramCoordinator row = programCoordinatorRepository.findByProgramIdAndUserId(programId, userId)
				.orElseThrow(() -> new InvalidRequestException("This coordinator does not run the program."));
		programCoordinatorRepository.delete(row);
		return refs(program);
	}

	private List<CoordinatorProgramResponse.CoordinatorRef> refs(Program program) {
		return programService.coordinatorsOf(program);
	}
}
