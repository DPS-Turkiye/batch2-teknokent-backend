package com.dps.teknokent.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.responses.ProgramEligibilityResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramEligibleEmailRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.EmailDomains;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Who may join a program that limits its members (İŞKUR: @metu.edu.tr and on the coordinator's list). Every way
 * into a program calls this, whoever is adding the person, so an admin cannot bypass it either. A program with
 * neither rule set lets everyone in, as before.
 */
@Service
@RequiredArgsConstructor
public class ProgramEligibilityService {

	private final ProgramRepository programRepository;
	private final ProgramEligibleEmailRepository eligibleEmailRepository;
	private final AppUserRepository appUserRepository;
	private final ProgramStartupRepository programStartupRepository;

	@Transactional(readOnly = true)
	public void requirePerson(UUID programId, String email) {
		if (programId != null) {
			programRepository.findById(programId).ifPresent(program -> requirePerson(program, email));
		}
	}

	public void requirePerson(Program program, String email) {
		refusal(program, email).ifPresent(message -> {
			throw new InvalidRequestException(message);
		});
	}

	/** Sign-up asks this as soon as a program is picked, so a student is not refused only after the onboarding. */
	@Transactional(readOnly = true)
	public ProgramEligibilityResponse check(UUID programId, String email) {
		Program program = programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
		return refusal(program, email).map(ProgramEligibilityResponse::refused)
				.orElseGet(ProgramEligibilityResponse::allowed);
	}

	/** Why this address may not join the program, in the caller's language; empty when it may. */
	private Optional<String> refusal(Program program, String email) {
		if (!restricts(program)) {
			return Optional.empty();
		}
		String normalized = EmailDomains.normalizeEmail(email);
		if (!EmailDomains.matches(normalized, program.getEligibleEmailDomain())) {
			return Optional.of(Texts.forRequest("error.programEligibilityDomain", normalized,
					"@" + program.getEligibleEmailDomain()));
		}
		if (program.isRosterOnly() && !eligibleEmailRepository.existsByProgramIdAndEmail(program.getId(), normalized)) {
			return Optional.of(Texts.forRequest("error.programEligibilityList", normalized));
		}
		return Optional.empty();
	}

	/** A team joins only when every member may. */
	@Transactional(readOnly = true)
	public void requireTeam(Program program, UUID organizationId) {
		if (!restricts(program)) {
			return;
		}
		appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId)
				.forEach(member -> requirePerson(program, member.getEmail()));
	}

	/** Someone joining a team must qualify for every restricted program the team is in. */
	@Transactional(readOnly = true)
	public void requireTeamMember(UUID organizationId, String email) {
		List<UUID> programIds = programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId)
				.stream()
				.map(ProgramStartup::getProgramId)
				.toList();
		programRepository.findAllById(programIds).forEach(program -> requirePerson(program, email));
	}

	public void requireMentor(UUID programId, AppUser mentor) {
		requirePerson(programId, mentor.getEmail());
	}

	public static boolean restricts(Program program) {
		return program.getEligibleEmailDomain() != null || program.isRosterOnly();
	}
}
