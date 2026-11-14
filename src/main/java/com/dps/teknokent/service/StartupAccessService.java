package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Who may see a startup's own data from outside its team. Admins are global; a coordinator is staff only for the
 * startups in a program they run (created or were added to), never for every startup on the platform.
 */
@Service
@RequiredArgsConstructor
public class StartupAccessService {

	private final DomainLookupService domainLookupService;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;

	/** Whether the user holds the coordinator permission and runs a program the startup is in. */
	@Transactional(readOnly = true)
	public boolean coordinates(AppUser actor, UUID organizationId) {
		if (organizationId == null || !actor.has(UserRole.COORDINATOR)) {
			return false;
		}
		return ProgramCoordination.coordinatesStartup(organizationId, actor.getId(), programStartupRepository,
				programRepository, programCoordinatorRepository);
	}

	/** A member of the startup, an admin, or a coordinator of its program. */
	@Transactional(readOnly = true)
	public boolean isTeamOrStaff(AppUser actor, UUID organizationId) {
		boolean teamMember = organizationId != null && actor.getOrganization() != null
				&& organizationId.equals(actor.getOrganization().getId());
		return teamMember || actor.has(UserRole.ADMIN) || coordinates(actor, organizationId);
	}

	/** {@link #isTeamOrStaff}, or 403. */
	@Transactional(readOnly = true)
	public AppUser requireTeamOrStaff(UUID actorId, UUID organizationId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!isTeamOrStaff(actor, organizationId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.startupTeamOnly"));
		}
		return actor;
	}
}
