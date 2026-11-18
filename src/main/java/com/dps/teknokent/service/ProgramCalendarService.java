package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.MemberProgramEventResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

import lombok.RequiredArgsConstructor;

/**
 * Program events as a member sees them: a startup's team or an accepted mentor,
 * never the roster. Next to the program's own events come its weekly plan's
 * special days and sessions ({@link ProgramPlanCalendarService}).
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
	private final ProgramPlanCalendarService planCalendar;

	@Transactional(readOnly = true)
	public List<MemberProgramEventResponse> eventsFor(UUID actorId, Instant from, Instant to) {
		return eventsFor(actorId, from, to, LocalDate.now(MeetingSlotTimes.APP_ZONE));
	}

	/**
	 * The program's own events and its plan's special days whatever their date; a session of its weekly plan only once
	 * its week has begun by {@code today}. The plan's entries go to approved accounts only, as its weeks do.
	 */
	List<MemberProgramEventResponse> eventsFor(UUID actorId, Instant from, Instant to, LocalDate today) {
		if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
			throw new InvalidRequestException("Choose a range of up to 120 days.");
		}
		AppUser actor = domainLookupService.requireUser(actorId);
		List<UUID> programIds = programsOf(actor);
		if (programIds.isEmpty()) {
			return List.of();
		}
		List<Program> programs = programRepository.findAllById(programIds);
		Map<UUID, String> names = programs.stream()
				.collect(Collectors.toMap(Program::getId, Program::getName, (left, right) -> left));
		List<ProgramEvent> events = programEventRepository
				.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(programIds, from, to);
		List<MemberProgramEventResponse> entries = new ArrayList<>();
		events.forEach(event -> entries.add(MemberProgramEventResponse.of(event, names.get(event.getProgramId()))));
		if (actor.getStatus() == UserAccountStatus.APPROVED) {
			planCalendar.forParticipants(programs, today, from, to, events)
					.forEach(entry -> entries.add(new MemberProgramEventResponse(entry.id(), entry.programId(),
							names.get(entry.programId()), entry.title(), entry.kind(), entry.startsAt(),
							entry.endsAt(), null, entry.plan())));
		}
		entries.sort(Comparator.comparing(MemberProgramEventResponse::startsAt));
		return entries;
	}

	/** Both sides for an account that mentors in some programs and is a mentee in others. */
	private List<UUID> programsOf(AppUser actor) {
		Set<UUID> programIds = new LinkedHashSet<>();
		if (actor.has(UserRole.MENTOR)) {
			programMentorRepository
					.findByMentorIdAndStatusOrderByCreatedAtDesc(actor.getId(), ProgramMentorStatus.ACCEPTED)
					.forEach(row -> programIds.add(row.getProgramId()));
		}
		if (actor.has(UserRole.MENTEE) && actor.getOrganization() != null) {
			programStartupRepository
					.findByOrganizationIdOrderByCreatedAtAsc(actor.getOrganization().getId())
					.forEach(row -> programIds.add(row.getProgramId()));
		}
		return List.copyOf(programIds);
	}
}
