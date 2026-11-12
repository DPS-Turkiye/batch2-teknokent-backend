package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.NewCoordinatorInput;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/** Who runs a program: its creator plus the coordinators added in Program Ayarları → Koordinatörler. */
@Service
@RequiredArgsConstructor
public class ProgramCoordinatorService {

	private final CoordinatorProgramService programService;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;
	private final AdminCoordinatorService adminCoordinatorService;

	@Transactional(readOnly = true)
	public List<CoordinatorProgramResponse.CoordinatorRef> list(UUID actorId, UUID programId) {
		return refs(programService.requireManagedProgram(actorId, programId));
	}

	/** Approved coordinators who could be added, i.e. not already running this program. */
	@Transactional(readOnly = true)
	public List<CoordinatorProgramResponse.CoordinatorRef> candidates(UUID actorId, UUID programId) {
		Program program = programService.requireManagedProgram(actorId, programId);
		Set<UUID> current = Set.copyOf(ProgramCoordination.coordinatorIds(program, programCoordinatorRepository));
		return appUserRepository.findByPermissionAndStatusOrderByFullNameAsc(UserRole.COORDINATOR, UserAccountStatus.APPROVED)
				.stream()
				.filter(user -> !current.contains(user.getId()))
				.map(user -> new CoordinatorProgramResponse.CoordinatorRef(user.getId(), user.getFullName(), false))
				.toList();
	}

	@Transactional
	public List<CoordinatorProgramResponse.CoordinatorRef> add(UUID actorId, UUID programId, UUID userId) {
		requireAdmin(actorId);
		Program program = programService.requireManagedProgram(actorId, programId);
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
		if (!user.has(UserRole.COORDINATOR) || user.getStatus() != UserAccountStatus.APPROVED) {
			throw new InvalidRequestException("Only an approved coordinator can run a program.");
		}
		return attach(actorId, program, user);
	}

	/**
	 * The admin typed an email in Koordinatörler: an approved coordinator joins as is, an email with no account is
	 * invited exactly as in "Yeni Program Oluştur" (account now, a mail naming the program after commit).
	 */
	@Transactional
	public List<CoordinatorProgramResponse.CoordinatorRef> invite(UUID actorId, UUID programId,
			NewCoordinatorInput invite) {
		requireAdmin(actorId);
		Program program = programService.requireManagedProgram(actorId, programId);
		AdminCoordinatorService.CheckedInvite checked =
				adminCoordinatorService.checkNewProgramCoordinator(actorId, invite.email(), invite.fullName());
		if (checked.existing() != null
				&& ProgramCoordination.coordinates(program, checked.existing().getId(), programCoordinatorRepository)) {
			throw new InvalidRequestException("This coordinator already runs the program.");
		}
		return attach(actorId, program, adminCoordinatorService.coordinatorForNewProgram(actorId, checked, program.getName()));
	}

	private List<CoordinatorProgramResponse.CoordinatorRef> attach(UUID actorId, Program program, AppUser user) {
		if (ProgramCoordination.coordinates(program, user.getId(), programCoordinatorRepository)) {
			throw new InvalidRequestException("This coordinator already runs the program.");
		}
		ProgramCoordinator row = new ProgramCoordinator();
		row.setId(UUID.randomUUID());
		row.setProgramId(program.getId());
		row.setUserId(user.getId());
		row.setAddedBy(actorId);
		row.setCreatedAt(Instant.now());
		programCoordinatorRepository.save(row);
		notificationService.create(
				user,
				Texts.get(user, "notification.addedAsCoordinator.title"),
				Texts.get(user, "notification.addedAsCoordinator.body", program.getName()),
				"/coordinator/programs/" + program.getId());
		return refs(program);
	}

	/** The creator cannot be removed: the program is theirs, and removing them could leave it with nobody. */
	@Transactional
	public List<CoordinatorProgramResponse.CoordinatorRef> remove(UUID actorId, UUID programId, UUID userId) {
		requireAdmin(actorId);
		Program program = programService.requireManagedProgram(actorId, programId);
		if (program.getCreatedBy().equals(userId)) {
			throw new InvalidRequestException("The coordinator who created the program cannot be removed.");
		}
		ProgramCoordinator row = programCoordinatorRepository.findByProgramIdAndUserId(programId, userId)
				.orElseThrow(() -> new InvalidRequestException("This coordinator does not run the program."));
		if (programService.coordinatorsOf(program).size() <= 1) {
			throw new InvalidRequestException("A program keeps at least one coordinator.");
		}
		programCoordinatorRepository.delete(row);
		return refs(program);
	}

	/** The admin assigns and changes a program's coordinators; coordinators only see them. */
	private void requireAdmin(UUID actorId) {
		if (!programService.requireCoordinatorOrAdmin(actorId).has(UserRole.ADMIN)) {
			throw new InvalidRequestException("Only an admin changes a program's coordinators.");
		}
	}

	private List<CoordinatorProgramResponse.CoordinatorRef> refs(Program program) {
		return programService.coordinatorsOf(program);
	}
}
