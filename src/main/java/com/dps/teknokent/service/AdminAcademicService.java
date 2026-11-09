package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AdminAcademicResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AppUserIdentity;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserIdentityRepository;
import com.dps.teknokent.repository.InstitutionAdvisingRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;

import lombok.RequiredArgsConstructor;

/** Admin › Kullanıcılar › Akademisyenler: every academician the institution lists, signed in or not. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAcademicService {

	private final DomainLookupService domainLookupService;
	private final InstitutionPersonRepository people;
	private final InstitutionAdvisingRepository advising;
	private final AppUserIdentityRepository identities;
	private final CoordinatorProgramService coordinatorProgramService;

	public List<AdminAcademicResponse> list(UUID actorId) {
		requireAdmin(actorId);
		return directory(true);
	}

	/**
	 * An Office Hours program's Katılımcılar › Akademisyenler: every academician the institution lists as active
	 * takes part, without invites (board). For the program's coordinators and the admin; no e-mail or sign-in time.
	 */
	public List<AdminAcademicResponse> forProgram(UUID actorId, UUID programId) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		if (program.getProgramType() != ProgramType.OFFICE_HOURS) {
			throw new InvalidRequestException("Academicians are listed for Office Hours programs only.");
		}
		return directory(false).stream().filter(AdminAcademicResponse::active).toList();
	}

	private List<AdminAcademicResponse> directory(boolean contactDetails) {
		List<InstitutionPerson> academics = people.findByAcademicTrueOrderByFullNameAsc();
		Map<String, Long> advisees = advising.countAdviseesByAdvisor().stream()
				.collect(Collectors.toMap(load -> key(load.getInstitution(), load.getAdvisorPersistentId()),
						InstitutionAdvisingRepository.AdvisorLoad::getAdvisees, Long::sum));
		List<UUID> userIds = academics.stream()
				.map(InstitutionPerson::getUser)
				.filter(Objects::nonNull)
				.map(AppUser::getId)
				.toList();
		Map<UUID, Instant> lastLogins = !contactDetails || userIds.isEmpty()
				? Map.of()
				: identities.findByUserIdIn(userIds).stream()
						.collect(Collectors.toMap(identity -> identity.getUser().getId(),
								AppUserIdentity::getLastLoginAt, (a, b) -> a.isAfter(b) ? a : b));
		return academics.stream()
				.map(person -> {
					UUID userId = person.getUser() == null ? null : person.getUser().getId();
					return new AdminAcademicResponse(
							person.getId(),
							userId,
							person.getFullName(),
							contactDetails ? person.getEmail() : null,
							person.getAcademicTitle(),
							person.getFaculty(),
							person.getDepartmentCode(),
							person.getDepartmentName(),
							person.isActive(),
							userId != null,
							contactDetails && userId != null ? lastLogins.get(userId) : null,
							advisees.getOrDefault(key(person.getInstitution(), person.getPersistentId()), 0L));
				})
				.toList();
	}

	private void requireAdmin(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!actor.has(UserRole.ADMIN)) {
			throw new InvalidRequestException("Only an admin can list academicians.");
		}
	}

	private static String key(String institution, String persistentId) {
		return institution + "|" + persistentId;
	}
}
