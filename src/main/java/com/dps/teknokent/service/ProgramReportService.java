package com.dps.teknokent.service;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AdminOverviewResponse;
import com.dps.teknokent.dto.responses.PageResponse;
import com.dps.teknokent.dto.responses.ProgramMeetingFeedbackResponse;
import com.dps.teknokent.dto.responses.ProgramMeetingRatingsResponse;
import com.dps.teknokent.dto.responses.ProgramSessionsResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

import lombok.RequiredArgsConstructor;

/** Program raporu: the Raporlama numbers for one program. An admin reads any program, a coordinator their own. */
@Service
@RequiredArgsConstructor
public class ProgramReportService {

	private static final int SCALE = 5;
	static final int MAX_FEEDBACK_PAGE_SIZE = 50;
	// Keeps page × size far from an int overflow in the offset.
	static final int MAX_FEEDBACK_PAGE = 10_000;
	private static final Collator TURKISH = Collator.getInstance(Locale.forLanguageTag("tr"));

	private final CoordinatorProgramService programService;
	private final AppUserRepository appUserRepository;
	private final AdminOverviewService adminOverviewService;
	private final ProgramStartupRepository programStartupRepository;
	private final MeetingRepository meetingRepository;
	private final MeetingFeedbackRepository meetingFeedbackRepository;

	@Transactional(readOnly = true)
	public AdminOverviewResponse report(UUID actorId, UUID programId) {
		return adminOverviewService.programReport(programService.requireManagedProgram(actorId, programId));
	}

	/**
	 * The mentees' post-meeting ratings for the program's meetings held since {@code from} (all of them when null).
	 * Numbers only: the free-text note a mentee may leave is never read here.
	 */
	@Transactional(readOnly = true)
	public ProgramMeetingRatingsResponse meetingRatings(UUID actorId, UUID programId, LocalDate from) {
		Program program = programService.requireManagedProgram(actorId, programId);
		List<UUID> held = heldMeetingIds(program, from);
		List<MeetingFeedback> feedback = held.isEmpty()
				? List.of()
				: meetingFeedbackRepository.findByMeetingIdInAndAuthorRole(held, UserRole.MENTEE);
		return summarize(program.getId(), held.size(), feedback);
	}

	/**
	 * Who rated whom: every feedback, mentor's and mentees', on the meetings {@link #meetingRatings} counts, newest
	 * meeting first. It carries names and free-text notes, so it is for the program's coordinators and admins only,
	 * and anyone else is refused outright (403) rather than with the report's "can't change this program" 400.
	 */
	@Transactional(readOnly = true)
	public PageResponse<ProgramMeetingFeedbackResponse> meetingFeedback(
			UUID actorId, UUID programId, LocalDate from, int page, int size) {
		Program program = requireFeedbackReader(actorId, programId);
		int safePage = Math.clamp(page, 0, MAX_FEEDBACK_PAGE);
		int safeSize = Math.clamp(size, 1, MAX_FEEDBACK_PAGE_SIZE);
		List<UUID> held = heldMeetingIds(program, from);
		if (held.isEmpty()) {
			return PageResponse.of(List.of(), safePage, safeSize, 0);
		}
		Page<MeetingFeedback> rows = meetingFeedbackRepository.findByMeetingIdIn(held, PageRequest.of(
				safePage, safeSize,
				Sort.by(Sort.Order.desc("meeting.scheduledAt"), Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
		return PageResponse.from(rows.map(ProgramMeetingFeedbackResponse::from));
	}

	private Program requireFeedbackReader(UUID actorId, UUID programId) {
		try {
			return programService.requireManagedProgram(actorId, programId);
		} catch (InvalidRequestException refused) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Only the program's coordinators and admins can read its meeting feedback.");
		}
	}

	/** The program's meetings that took place: not cancelled, already started, and on or after {@code from}. */
	private List<UUID> heldMeetingIds(Program program, LocalDate from) {
		Set<UUID> teams = teamsOf(program);
		if (teams.isEmpty()) {
			return List.of();
		}
		Instant since = from == null ? Instant.EPOCH : from.atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant();
		return meetingRepository.findHeldMeetingIdsOfProgram(program.getId(), teams, since, Instant.now());
	}

	/**
	 * Sessions per mentor and per team between {@code from} and {@code to}: inclusive days in the app's time zone,
	 * the current month when left out. Only meetings the mentor confirmed as held count, and a group session (one
	 * meeting row per team at the same start) counts once for its mentor.
	 */
	@Transactional(readOnly = true)
	public ProgramSessionsResponse sessions(UUID actorId, UUID programId, LocalDate from, LocalDate to) {
		Program program = programService.requireManagedProgram(actorId, programId);
		LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		LocalDate first = from != null ? from : today.withDayOfMonth(1);
		LocalDate last = to != null ? to : today.with(TemporalAdjusters.lastDayOfMonth());
		if (last.isBefore(first)) {
			throw new InvalidRequestException("The report cannot end before it starts.");
		}
		Set<UUID> teams = teamsOf(program);
		List<Meeting> held = teams.isEmpty()
				? List.of()
				: meetingRepository
						.findByOrganizationIdInAndHeldConfirmedAtIsNotNullAndScheduledAtGreaterThanEqualAndScheduledAtLessThan(
								teams,
								first.atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant(),
								last.plusDays(1).atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant())
						.stream()
						.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED)
						.toList();
		Set<UUID> heldTeams = new HashSet<>();
		held.forEach(meeting -> heldTeams.add(meeting.getOrganization().getId()));
		Map<UUID, String> teamEmails = heldTeams.isEmpty()
				? Map.of()
				: appUserRepository.findByOrganizationIdInOrderByFullNameAsc(heldTeams).stream()
						.collect(Collectors.groupingBy(
								user -> user.getOrganization().getId(),
								Collectors.mapping(AppUser::getEmail, Collectors.joining(", "))));
		return new ProgramSessionsResponse(
				program.getId(),
				first,
				last,
				rows(held.stream().filter(meeting -> meeting.getMentor() != null).toList(),
						meeting -> meeting.getMentor().getId(),
						meeting -> meeting.getMentor().getFullName(),
						meeting -> meeting.getMentor().getEmail()),
				rows(held,
						meeting -> meeting.getOrganization().getId(),
						meeting -> meeting.getOrganization().getName(),
						meeting -> teamEmails.get(meeting.getOrganization().getId())));
	}

	static List<ProgramSessionsResponse.Row> rows(
			List<Meeting> meetings,
			Function<Meeting, UUID> key,
			Function<Meeting, String> name,
			Function<Meeting, String> email) {
		Map<UUID, List<Meeting>> groups = new LinkedHashMap<>();
		for (Meeting meeting : meetings) {
			groups.computeIfAbsent(key.apply(meeting), id -> new ArrayList<>()).add(meeting);
		}
		List<ProgramSessionsResponse.Row> rows = new ArrayList<>();
		groups.forEach((id, group) -> {
			Map<Instant, Integer> slots = new HashMap<>();
			for (Meeting meeting : group) {
				int minutes = meeting.getDurationMinutes() == null ? 0 : meeting.getDurationMinutes();
				slots.merge(meeting.getScheduledAt(), minutes, Math::max);
			}
			long days = slots.keySet().stream()
					.map(start -> start.atZone(MeetingSlotTimes.APP_ZONE).toLocalDate())
					.distinct()
					.count();
			int minutes = slots.values().stream().mapToInt(Integer::intValue).sum();
			Meeting any = group.get(0);
			rows.add(new ProgramSessionsResponse.Row(id, name.apply(any), email.apply(any), slots.size(), minutes,
					(int) days));
		});
		rows.sort(Comparator.comparing(ProgramSessionsResponse.Row::name, Comparator.nullsLast(TURKISH)));
		return rows;
	}

	/**
	 * The program's startups, and the personal teams of its students: a peer-mentoring student files sessions under a
	 * hidden team of one that never joins the program as a startup, but their sessions count with their own program.
	 */
	private Set<UUID> teamsOf(Program program) {
		Set<UUID> teams = new HashSet<>();
		for (ProgramStartup startup : programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId())) {
			teams.add(startup.getOrganizationId());
		}
		for (AppUser student : appUserRepository.findPersonalTeamStudentsOfProgram(program.getId())) {
			teams.add(student.getOrganization().getId());
		}
		return teams;
	}

	static ProgramMeetingRatingsResponse summarize(UUID programId, int heldMeetings, List<MeetingFeedback> feedback) {
		List<Integer> sums = new ArrayList<>();
		List<Integer> counts = new ArrayList<>();
		List<Integer> distribution = new ArrayList<>(List.of(0, 0, 0, 0, 0));
		Set<UUID> ratedMeetings = new HashSet<>();
		int total = 0;
		int answers = 0;
		for (MeetingFeedback item : feedback) {
			ratedMeetings.add(item.getMeeting().getId());
			List<Integer> ratings = item.getRatings();
			for (int question = 0; question < ratings.size(); question++) {
				int rating = ratings.get(question);
				if (rating < 1 || rating > SCALE) {
					continue;
				}
				while (sums.size() <= question) {
					sums.add(0);
					counts.add(0);
				}
				sums.set(question, sums.get(question) + rating);
				counts.set(question, counts.get(question) + 1);
				distribution.set(rating - 1, distribution.get(rating - 1) + 1);
				total += rating;
				answers++;
			}
		}
		List<Double> questionAverages = new ArrayList<>();
		for (int question = 0; question < sums.size(); question++) {
			questionAverages.add(counts.get(question) == 0 ? null : round((double) sums.get(question) / counts.get(question)));
		}
		return new ProgramMeetingRatingsResponse(
				programId,
				heldMeetings,
				ratedMeetings.size(),
				feedback.size(),
				answers == 0 ? null : round((double) total / answers),
				questionAverages,
				distribution);
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}
}
