package com.dps.teknokent.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramCalendarEntryKind;
import com.dps.teknokent.dto.responses.ProgramCalendarPlanItem;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramSpecialDay;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.repository.ProgramSpecialDayRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

/**
 * The calendar entries a program's weekly plan adds next to its own events: the sessions of its weeks (a lesson, a
 * workshop, a visit) and its special days (a bootcamp, a demo day). Callers check who may see which program; this
 * decides what of it goes out.
 *
 * <p>
 * A participant gets a special day ahead of time, like a program event, but a session only once its week has begun,
 * by the same rule as "Geçmiş haftalar" ({@link ProgramWeekService#reached}): a week the coordinator opened early shows
 * its sessions now, and a week they hid shows none. A coordinator gets every session.
 *
 * <p>
 * An entry that repeats a coordinator's own event is left out, so the day shows once: same program, same day in
 * Istanbul and the same title once case, spaces, punctuation and the dotted/dotless i are ignored. A session that
 * repeats a special day is left out the same way. Entries of the plan never hide each other otherwise.
 */
@Service
@RequiredArgsConstructor
public class ProgramPlanCalendarService {

	// "Konuşmacı: Sarper & Nihan" (or "Konuşmacılar:") in a week's topics; "Speaker:" for a plan written in English.
	private static final Pattern SPEAKER = Pattern.compile(
			"^\\s*(?:konuşmacı(?:lar)?|konusmaci(?:lar)?|speakers?)\\s*:\\s*(.+)$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private final ProgramWeekRepository programWeekRepository;
	private final ProgramSpecialDayRepository programSpecialDayRepository;

	/** {@code kind} is SESSION or SPECIAL_DAY. {@code endsAt} is null for an all-day entry or one with no end time. */
	public record Entry(
			UUID id,
			UUID programId,
			ProgramCalendarEntryKind kind,
			String title,
			Instant startsAt,
			@Nullable Instant endsAt,
			ProgramCalendarPlanItem plan) {
	}

	/**
	 * What a program's mentees and accepted mentors see between {@code from} and {@code to} (both inclusive): its
	 * special days, and the sessions of the weeks that have begun by {@code today}.
	 */
	@Transactional(readOnly = true)
	public List<Entry> forParticipants(Collection<Program> programs, LocalDate today, Instant from, Instant to,
			List<ProgramEvent> events) {
		return entries(programs, from, to, events, (program, plan) -> ProgramWeekService
				.reached(program, plan, List.of(), today).weeks().stream()
				.map(week -> week.plan())
				.toList());
	}

	/** What the coordinator's Takvim shows: every session and special day of the programs. */
	@Transactional(readOnly = true)
	public List<Entry> forCoordinator(Collection<Program> programs, Instant from, Instant to,
			List<ProgramEvent> events) {
		return entries(programs, from, to, events, (program, plan) -> plan.stream()
				.map(week -> ProgramWeekPlanService.weekOf(week, program.getStartDate(), program.getEndDate()))
				.toList());
	}

	private interface WeeksShown {
		List<ProgramWeekPlanResponse.Week> of(Program program, List<ProgramWeek> plan);
	}

	private List<Entry> entries(Collection<Program> programs, Instant from, Instant to, List<ProgramEvent> events,
			WeeksShown shown) {
		if (programs.isEmpty()) {
			return List.of();
		}
		Map<UUID, Program> byId = programs.stream()
				.collect(Collectors.toMap(Program::getId, Function.identity(), (left, right) -> left));
		Set<String> eventKeys = new HashSet<>();
		events.forEach(event -> eventKeys.add(key(event.getProgramId(), day(event.getStartsAt()), event.getTitle())));
		// What a session must not repeat: the coordinator's events and the special days shown.
		Set<String> taken = new HashSet<>(eventKeys);

		List<Entry> entries = new ArrayList<>();
		LocalDate firstDay = day(from);
		LocalDate lastDay = day(to);
		for (ProgramSpecialDay special : programSpecialDayRepository
				.findByProgramIdInAndDateBetweenOrderByDateAsc(byId.keySet(), firstDay, lastDay)) {
			Entry entry = specialDayEntry(special);
			String specialKey = key(special.getProgramId(), special.getDate(), entry.title());
			if (inRange(entry, from, to) && !eventKeys.contains(specialKey)) {
				taken.add(specialKey);
				entries.add(entry);
			}
		}

		Map<UUID, List<ProgramWeek>> plans = new HashMap<>();
		for (ProgramWeek week : programWeekRepository.findByProgramIdIn(byId.keySet())) {
			plans.computeIfAbsent(week.getProgramId(), key -> new ArrayList<>()).add(week);
		}
		for (Map.Entry<UUID, List<ProgramWeek>> plan : plans.entrySet()) {
			Program program = byId.get(plan.getKey());
			if (program == null) {
				continue;
			}
			List<ProgramWeek> weeks = plan.getValue().stream()
					.sorted(Comparator.comparingInt(ProgramWeek::getWeekNumber))
					.toList();
			for (ProgramWeekPlanResponse.Week week : shown.of(program, weeks)) {
				for (Entry entry : sessionEntries(program.getId(), week)) {
					// Not added to taken: two sessions of one day may share a title at different times.
					if (inRange(entry, from, to)
							&& !taken.contains(key(program.getId(), day(entry.startsAt()), entry.title()))) {
						entries.add(entry);
					}
				}
			}
		}
		entries.sort(Comparator.comparing(Entry::startsAt));
		return entries;
	}

	/**
	 * The week's sessions as calendar entries. A session without a title takes the week's; the week's speaker goes
	 * with the sessions on its lesson day: the first day the syllabus prints for the week, or, when it prints none,
	 * the week's first session (the program calendar's Monday need not be a session day).
	 */
	static List<Entry> sessionEntries(UUID programId, ProgramWeekPlanResponse.Week week) {
		String speaker = speakerOf(week.topics());
		@Nullable
		LocalDate lessonDay = week.datesFromSource() ? week.startDate()
				: week.sessions().stream().map(ProgramWeekPlanResponse.Session::date).min(Comparator.naturalOrder())
						.orElse(null);
		Map<String, Integer> seen = new HashMap<>();
		List<Entry> entries = new ArrayList<>();
		for (ProgramWeekPlanResponse.Session session : week.sessions()) {
			String sessionTitle = TextUtils.normalizeOptional(session.title());
			String title = sessionTitle == null ? week.title() : sessionTitle;
			// Two sessions with the same week, day, time and title still get ids of their own.
			String identity = String.join("|", "program-session", programId.toString(),
					Integer.toString(week.week()), session.date().toString(), String.valueOf(session.startTime()),
					title);
			int occurrence = seen.merge(identity, 1, Integer::sum);
			UUID id = UUID.nameUUIDFromBytes((identity + "|" + occurrence).getBytes(StandardCharsets.UTF_8));
			boolean lesson = session.date().equals(lessonDay);
			entries.add(new Entry(id, programId, ProgramCalendarEntryKind.SESSION, title,
					startOf(session.date(), session.startTime()),
					endOf(session.date(), session.startTime(), session.endTime()),
					new ProgramCalendarPlanItem(week.week(), week.title(),
							TextUtils.normalizeOptional(session.place()), lesson ? speaker : null, List.of(),
							session.startTime() == null)));
		}
		return entries;
	}

	static Entry specialDayEntry(ProgramSpecialDay day) {
		return new Entry(day.getId(), day.getProgramId(), ProgramCalendarEntryKind.SPECIAL_DAY, day.getTitle(),
				startOf(day.getDate(), day.getStartTime()),
				endOf(day.getDate(), day.getStartTime(), day.getEndTime()),
				new ProgramCalendarPlanItem(null, null, TextUtils.normalizeOptional(day.getPlace()), null,
						ProgramWeek.lines(day.getDetails()), day.getStartTime() == null));
	}

	static @Nullable String speakerOf(List<String> topics) {
		for (String topic : topics) {
			Matcher matcher = SPEAKER.matcher(topic);
			if (matcher.matches()) {
				return TextUtils.normalizeOptional(matcher.group(1));
			}
		}
		return null;
	}

	/** The title a duplicate is recognised by: see the class comment. */
	static String key(UUID programId, LocalDate day, @Nullable String title) {
		return programId + "|" + day + "|" + TextUtils.normalizeForComparison(title).replace('ı', 'i');
	}

	/** An all-day entry counts while any part of its day is in range; a timed one by its start. */
	private static boolean inRange(Entry entry, Instant from, Instant to) {
		if (entry.startsAt().isAfter(to)) {
			return false;
		}
		Instant last = entry.plan().allDay()
				? entry.startsAt().atZone(MeetingSlotTimes.APP_ZONE).plusDays(1).toInstant().minusNanos(1)
				: entry.startsAt();
		return !last.isBefore(from);
	}

	private static Instant startOf(LocalDate date, @Nullable LocalTime time) {
		return time == null ? date.atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant()
				: date.atTime(time).atZone(MeetingSlotTimes.APP_ZONE).toInstant();
	}

	private static @Nullable Instant endOf(LocalDate date, @Nullable LocalTime start, @Nullable LocalTime end) {
		return start == null || end == null || !end.isAfter(start) ? null
				: date.atTime(end).atZone(MeetingSlotTimes.APP_ZONE).toInstant();
	}

	private static LocalDate day(Instant instant) {
		return instant.atZone(MeetingSlotTimes.APP_ZONE).toLocalDate();
	}
}
