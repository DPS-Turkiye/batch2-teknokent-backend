package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.MemberProgramEventResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

import lombok.RequiredArgsConstructor;

/**
 * Program events as a member sees them: a startup's team or an accepted mentor,
 * never the roster.
 */
@Service
@RequiredArgsConstructor
public class ProgramCalendarService {

	static final Duration MAX_RANGE = Duration.ofDays(120);

	private final DomainLookupService domainLookupService;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramEventRepository programEventRepository;
	private final ProgramRepository programRepository;

	@Transactional(readOnly = true)
	public List<MemberProgramEventResponse> eventsFor(UUID actorId, Instant from, Instant to) {
		if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
			throw new InvalidRequestException("Choose a range of up to 120 days.");
		}
		List<UUID> programIds = programsOf(domainLookupService.requireUser(actorId));
		if (programIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> names = programRepository.findAllById(programIds).stream()
				.collect(Collectors.toMap(Program::getId, Program::getName, (left, right) -> left));
		return programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(programIds, from, to)
				.stream()
				.map(event -> MemberProgramEventResponse.of(event, names.get(event.getProgramId())))
				.toList();
	}

	private List<UUID> programsOf(AppUser actor) {
		if (actor.getRole() == UserRole.MENTOR) {
			return programMentorRepository
					.findByMentorIdAndStatusOrderByCreatedAtDesc(actor.getId(), ProgramMentorStatus.ACCEPTED)
					.stream()
					.map(ProgramMentor::getProgramId)
					.distinct()
					.toList();
		}
		if (actor.getRole() == UserRole.MENTEE && actor.getOrganization() != null) {
			return programStartupRepository
					.findByOrganizationIdOrderByCreatedAtAsc(actor.getOrganization().getId())
					.stream()
					.map(ProgramStartup::getProgramId)
					.distinct()
					.toList();
		}
		return List.of();
	}
}
