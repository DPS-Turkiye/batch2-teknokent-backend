package com.dps.teknokent.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.CoordinatorSearchResult;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.RequiredArgsConstructor;

/**
 * The admin's "Koordinatör ata" email field: approved coordinators whose email starts with what was typed. Only a
 * short prefix match over coordinators, never other roles, and only a handful at a time, so it cannot be used to
 * page through every account.
 */
@Service
@RequiredArgsConstructor
public class CoordinatorSearchService {

	static final int MIN_QUERY_LENGTH = 3;
	static final int MAX_QUERY_LENGTH = 255;
	static final int MAX_RESULTS = 10;

	private final DomainLookupService domainLookupService;
	private final AppUserRepository appUserRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;

	/** With a {@code programId}, the coordinators who already run that program are left out. */
	@Transactional(readOnly = true)
	public List<CoordinatorSearchResult> search(UUID actorId, String query, UUID programId) {
		if (!domainLookupService.requireUser(actorId).has(UserRole.ADMIN)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can search coordinators.");
		}
		String prefix = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (prefix.length() < MIN_QUERY_LENGTH || prefix.length() > MAX_QUERY_LENGTH) {
			throw new InvalidRequestException(
					"Type between " + MIN_QUERY_LENGTH + " and " + MAX_QUERY_LENGTH + " characters to search.");
		}
		Set<UUID> excluded = programId == null ? Set.of() : Set.copyOf(currentCoordinators(programId));
		return appUserRepository.findByPermissionAndStatusAndEmailPrefix(
						UserRole.COORDINATOR,
						UserAccountStatus.APPROVED,
						escapeLike(prefix),
						PageRequest.of(0, MAX_RESULTS + excluded.size()))
				.stream()
				.filter(user -> !excluded.contains(user.getId()))
				.limit(MAX_RESULTS)
				.map(user -> new CoordinatorSearchResult(user.getId(), user.getFullName(), user.getEmail()))
				.toList();
	}

	private List<UUID> currentCoordinators(UUID programId) {
		Program program = programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
		return ProgramCoordination.coordinatorIds(program, programCoordinatorRepository);
	}

	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
