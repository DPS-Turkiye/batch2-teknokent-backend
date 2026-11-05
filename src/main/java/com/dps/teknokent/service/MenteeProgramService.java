package com.dps.teknokent.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

import lombok.RequiredArgsConstructor;

/**
 * Which program a mentee works in, and so which mentors they can reach: a mentee in a program sees and asks only
 * that program's accepted mentors. A startup outside every program keeps the whole mentor list.
 */
@Service
@RequiredArgsConstructor
public class MenteeProgramService {

	private final AppUserRepository appUserRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;

	/** The startup's chosen program, else the last program it joined. */
	public Optional<UUID> programOf(Organization organization) {
		if (organization.getActiveProgramId() != null) {
			return Optional.of(organization.getActiveProgramId());
		}
		List<ProgramStartup> joined = programStartupRepository
				.findByOrganizationIdOrderByCreatedAtAsc(organization.getId());
		return joined.isEmpty() ? Optional.empty() : Optional.of(joined.get(joined.size() - 1).getProgramId());
	}

	/** A mentee's program: their startup's, or for someone without a startup yet the one they signed up into. */
	public Optional<UUID> programOf(AppUser mentee) {
		if (mentee.getOrganization() != null) {
			return programOf(mentee.getOrganization());
		}
		return Optional.ofNullable(mentee.getProgramId());
	}

	/**
	 * The mentors {@code actorId} may see and ask, or empty when nothing limits them: anyone who is not a mentee,
	 * and a mentee outside every program.
	 */
	@Transactional(readOnly = true)
	public Optional<Set<UUID>> reachableMentorIds(UUID actorId) {
		if (actorId == null) {
			return Optional.empty();
		}
		return appUserRepository.findById(actorId)
				.filter(user -> user.getRole() == UserRole.MENTEE)
				.flatMap(this::programOf)
				.map(this::acceptedMentorIds);
	}

	public Set<UUID> acceptedMentorIds(UUID programId) {
		return programMentorRepository.findByProgramIdOrderByCreatedAtAsc(programId).stream()
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
				.map(ProgramMentor::getMentorId)
				.collect(Collectors.toSet());
	}
}
