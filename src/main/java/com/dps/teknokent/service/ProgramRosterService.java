package com.dps.teknokent.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.AddProgramEligibleEmailsRequest;
import com.dps.teknokent.dto.responses.ProgramEligibleEmailResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEligibleEmail;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramEligibleEmailRepository;
import com.dps.teknokent.util.EmailDomains;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * The coordinator's list of addresses allowed into a roster-only program. Removing an address does not take
 * anyone out of the program; it only stops new joins.
 */
@Service
@RequiredArgsConstructor
public class ProgramRosterService {

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramEligibleEmailRepository eligibleEmailRepository;
	private final AppUserRepository appUserRepository;

	@Transactional(readOnly = true)
	public List<ProgramEligibleEmailResponse> list(UUID actorId, UUID programId) {
		coordinatorProgramService.requireManagedProgram(actorId, programId);
		return rows(programId);
	}

	/** All or nothing: one bad address refuses the paste and names every bad one, so nothing is half-added. */
	@Transactional
	public List<ProgramEligibleEmailResponse> add(UUID actorId, UUID programId,
			AddProgramEligibleEmailsRequest request) {
		Program program = coordinatorProgramService.requireManagedProgram(actorId, programId);
		Set<String> emails = request.emails().stream()
				.map(EmailDomains::normalizeEmail)
				.filter(email -> !email.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		List<String> refused = emails.stream()
				.filter(email -> !EmailDomains.isEmail(email)
						|| !EmailDomains.matches(email, program.getEligibleEmailDomain()))
				.toList();
		if (!refused.isEmpty()) {
			throw new InvalidRequestException(Texts.forRequest("error.programRosterRefused", String.join(", ", refused)));
		}
		eligibleEmailRepository.findByProgramIdAndEmailIn(programId, emails)
				.forEach(existing -> emails.remove(existing.getEmail()));
		Instant now = Instant.now();
		eligibleEmailRepository.saveAll(emails.stream().map(email -> {
			ProgramEligibleEmail row = new ProgramEligibleEmail();
			row.setId(UUID.randomUUID());
			row.setProgramId(programId);
			row.setEmail(email);
			row.setAddedBy(actorId);
			row.setCreatedAt(now);
			return row;
		}).toList());
		return rows(programId);
	}

	@Transactional
	public List<ProgramEligibleEmailResponse> remove(UUID actorId, UUID programId, UUID entryId) {
		coordinatorProgramService.requireManagedProgram(actorId, programId);
		ProgramEligibleEmail row = eligibleEmailRepository.findByIdAndProgramId(entryId, programId)
				.orElseThrow(() -> new ResourceNotFoundException("List entry not found: " + entryId));
		eligibleEmailRepository.delete(row);
		return rows(programId);
	}

	private List<ProgramEligibleEmailResponse> rows(UUID programId) {
		List<ProgramEligibleEmail> rows = eligibleEmailRepository.findByProgramIdOrderByEmailAsc(programId);
		Set<String> registered = rows.isEmpty() ? Set.of()
				: Set.copyOf(appUserRepository.findRegisteredEmails(rows.stream().map(ProgramEligibleEmail::getEmail).toList()));
		return rows.stream().map(row -> ProgramEligibleEmailResponse.of(row, registered.contains(row.getEmail()))).toList();
	}
}
