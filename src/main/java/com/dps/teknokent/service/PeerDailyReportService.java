package com.dps.teknokent.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.PeerMentorSkill;
import com.dps.teknokent.domain.PeerReportStatus;
import com.dps.teknokent.dto.requests.SavePeerActivityRequest;
import com.dps.teknokent.dto.requests.SavePeerDailyReportRequest;
import com.dps.teknokent.dto.responses.PeerActivityResponse;
import com.dps.teknokent.dto.responses.PeerDailyReportResponse;
import com.dps.teknokent.dto.responses.PeerReportMeetingResponse;
import com.dps.teknokent.dto.responses.PeerReportWeekResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.PeerActivity;
import com.dps.teknokent.entity.PeerDailyReport;
import com.dps.teknokent.entity.PeerMeetingReport;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.PeerActivityRepository;
import com.dps.teknokent.repository.PeerDailyReportRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * "Günlük Rapor": one per working day, due by 23:59 (program time). The day's meetings come from the calendar and its
 * activities from "Faaliyet ekle"; the mentor adds only their own words. It cannot be sent while a held meeting of the
 * day lacks a sent meeting report.
 */
@Service
@RequiredArgsConstructor
public class PeerDailyReportService {

	static final int WORKING_DAYS = 5;

	private final PeerReportAccess access;
	private final PeerReportCalendar calendar;
	private final PeerMeetingReportService meetingReports;
	private final PeerDailyReportRepository dailyRepository;
	private final PeerActivityRepository activityRepository;
	private final AppUserRepository userRepository;

	/** The five day cards of the week starting on {@code weekStart} (a Monday). */
	@Transactional(readOnly = true)
	public PeerReportWeekResponse week(UUID actorId, LocalDate weekStart) {
		AppUser mentor = access.requireMentor(actorId);
		requireMonday(weekStart);
		List<PeerReportWeekResponse.Day> days = days(mentor, weekStart);
		int sent = (int) days.stream().filter(day -> day.status() != null && day.status().sent()).count();
		return new PeerReportWeekResponse(weekStart, days, sent);
	}

	@Transactional(readOnly = true)
	public PeerDailyReportResponse day(UUID actorId, LocalDate date) {
		AppUser mentor = access.requireMentor(actorId);
		requireWorkingDay(date);
		return response(mentor, date, dailyRepository.findByMentorIdAndReportDate(mentor.getId(), date).orElse(null));
	}

	@Transactional
	public PeerDailyReportResponse save(UUID actorId, LocalDate date, SavePeerDailyReportRequest request) {
		AppUser mentor = access.requireMentor(actorId);
		requireReportableDay(date);
		PeerDailyReport report = dailyRepository.findByMentorIdAndReportDate(mentor.getId(), date)
				.orElseGet(() -> newReport(mentor, date));
		requireEditable(report.getStatus());
		report.setDidToday(PeerMeetingReportService.trimmed(request.didToday()));
		report.setChallenge(PeerMeetingReportService.trimmed(request.challenge()));
		report.getSkills().clear();
		if (request.skills() != null) {
			report.getSkills().addAll(request.skills());
		}
		report.setLearned(PeerMeetingReportService.trimmed(request.learned()));
		report.setTomorrowPlan(PeerMeetingReportService.trimmed(request.tomorrowPlan()));
		report.setUpdatedAt(calendar.now());
		return response(mentor, date, dailyRepository.saveAndFlush(report));
	}

	/** Sends the day for review: every held meeting of the day needs a sent report, and the four questions an answer. */
	@Transactional
	public PeerDailyReportResponse submit(UUID actorId, LocalDate date) {
		AppUser mentor = access.requireMentor(actorId);
		requireReportableDay(date);
		PeerDailyReport report = dailyRepository.findByMentorIdAndReportDate(mentor.getId(), date)
				.orElseThrow(() -> new ConflictException(Texts.forRequest("error.peerReportNotStarted")));
		requireEditable(report.getStatus());
		if (missingMeetingReports(mentor, date) > 0) {
			throw new ConflictException(Texts.forRequest("error.peerDailyMissingMeetingReports"));
		}
		if (PeerMeetingReportService.blank(report.getDidToday())
				|| PeerMeetingReportService.blank(report.getChallenge())
				|| PeerMeetingReportService.blank(report.getLearned())
				|| PeerMeetingReportService.blank(report.getTomorrowPlan())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerReportIncomplete", "EVALUATION"),
					"PEER_REPORT_INCOMPLETE");
		}
		report.submit(calendar.now());
		return response(mentor, date, dailyRepository.saveAndFlush(report));
	}

	@Transactional
	public PeerActivityResponse addActivity(UUID actorId, SavePeerActivityRequest request) {
		AppUser mentor = access.requireMentor(actorId);
		PeerActivity activity = new PeerActivity();
		activity.setId(UUID.randomUUID());
		activity.setMentorId(mentor.getId());
		activity.setProgramId(access.programOf(mentor));
		activity.setCreatedAt(calendar.now());
		apply(mentor, activity, request);
		return activityResponse(activityRepository.saveAndFlush(activity), menteeNames(List.of(activity)));
	}

	@Transactional
	public PeerActivityResponse updateActivity(UUID actorId, UUID activityId, SavePeerActivityRequest request) {
		AppUser mentor = access.requireMentor(actorId);
		PeerActivity activity = ownActivity(mentor, activityId);
		requireDayEditable(mentor, activity.getActivityDate());
		apply(mentor, activity, request);
		return activityResponse(activityRepository.saveAndFlush(activity), menteeNames(List.of(activity)));
	}

	@Transactional
	public void deleteActivity(UUID actorId, UUID activityId) {
		AppUser mentor = access.requireMentor(actorId);
		PeerActivity activity = ownActivity(mentor, activityId);
		requireDayEditable(mentor, activity.getActivityDate());
		activityRepository.delete(activity);
	}

	/** The day cards of a week, Monday to Friday. Shared with the weekly summary. */
	List<PeerReportWeekResponse.Day> days(AppUser mentor, LocalDate weekStart) {
		LocalDate friday = weekStart.plusDays(WORKING_DAYS - 1L);
		Map<LocalDate, PeerDailyReport> reports = dailyRepository
				.findByMentorIdAndReportDateBetween(mentor.getId(), weekStart, friday).stream()
				.collect(Collectors.toMap(PeerDailyReport::getReportDate, Function.identity()));
		Map<LocalDate, Long> activityCounts = activities(mentor, weekStart, friday).stream()
				.collect(Collectors.groupingBy(PeerActivity::getActivityDate, Collectors.counting()));
		List<Meeting> meetings = access.heldMeetings(mentor, calendar.startOf(weekStart),
				calendar.startOf(friday.plusDays(1)));
		Map<UUID, PeerMeetingReport> meetingReportsById = meetingReports.reportsFor(mentor, meetings);
		List<PeerReportWeekResponse.Day> days = new ArrayList<>();
		for (int offset = 0; offset < WORKING_DAYS; offset++) {
			LocalDate date = weekStart.plusDays(offset);
			List<Meeting> ofDay = meetings.stream()
					.filter(meeting -> calendar.dateOf(meeting.getScheduledAt()).equals(date))
					.toList();
			PeerDailyReport report = reports.get(date);
			days.add(new PeerReportWeekResponse.Day(date, report == null ? null : report.getStatus(), ofDay.size(),
					activityCounts.getOrDefault(date, 0L).intValue(), unsent(ofDay, meetingReportsById)));
		}
		return days;
	}

	/** Activities of the mentor from {@code from} to {@code to}, both included. Shared with the weekly summary. */
	List<PeerActivity> activities(AppUser mentor, LocalDate from, LocalDate to) {
		return activityRepository.findByMentorIdAndActivityDateBetweenOrderByActivityDateAscCreatedAtAsc(
				mentor.getId(), from, to);
	}

	static void requireMonday(LocalDate weekStart) {
		if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
			throw new InvalidRequestException(Texts.forRequest("error.peerReportWeekStart"));
		}
	}

	private PeerDailyReportResponse response(AppUser mentor, LocalDate date, PeerDailyReport report) {
		List<Meeting> meetings = meetingsOf(mentor, date);
		Map<UUID, PeerMeetingReport> reports = meetingReports.reportsFor(mentor, meetings);
		List<PeerReportMeetingResponse> rows = meetingReports.rows(mentor, meetings);
		List<PeerActivity> activities = activities(mentor, date, date);
		Map<UUID, String> names = menteeNames(activities);
		Set<PeerMentorSkill> suggested = EnumSet.noneOf(PeerMentorSkill.class);
		reports.values().forEach(meetingReport -> suggested.addAll(meetingReport.getSkills()));
		int recorded = rows.stream().mapToInt(PeerReportMeetingResponse::durationMinutes).sum()
				+ activities.stream().mapToInt(PeerActivity::getDurationMinutes).sum();
		PeerReportStatus status = report == null ? null : report.getStatus();
		boolean open = status == null || status.editable();
		boolean late = report != null && report.getSubmittedAt() != null
				? report.getSubmittedAt().isAfter(calendar.dailyDeadline(date))
				: open && calendar.now().isAfter(calendar.dailyDeadline(date));
		return new PeerDailyReportResponse(
				date,
				report == null ? null : report.getId(),
				status,
				open && !date.isAfter(calendar.today()),
				calendar.dailyDeadline(date),
				late,
				rows,
				activities.stream().map(activity -> activityResponse(activity, names)).toList(),
				report == null ? null : report.getDidToday(),
				report == null ? null : report.getChallenge(),
				report == null ? Set.of() : PeerMeetingReportService.ordered(report.getSkills(), PeerMentorSkill.class),
				report == null ? null : report.getLearned(),
				report == null ? null : report.getTomorrowPlan(),
				suggested,
				recorded,
				unsent(meetings, reports),
				report == null ? null : report.getSubmittedAt(),
				report == null ? null : report.getRevisionNote(),
				report == null ? null : report.getUpdatedAt());
	}

	private List<Meeting> meetingsOf(AppUser mentor, LocalDate date) {
		return access.heldMeetings(mentor, calendar.startOf(date), calendar.startOf(date.plusDays(1)));
	}

	private int missingMeetingReports(AppUser mentor, LocalDate date) {
		List<Meeting> meetings = meetingsOf(mentor, date);
		return unsent(meetings, meetingReports.reportsFor(mentor, meetings));
	}

	private static int unsent(List<Meeting> meetings, Map<UUID, PeerMeetingReport> reports) {
		return (int) meetings.stream()
				.filter(meeting -> !PeerMeetingReportService.sent(reports.get(meeting.getId())))
				.count();
	}

	private void apply(AppUser mentor, PeerActivity activity, SavePeerActivityRequest request) {
		requireReportableDay(request.activityDate());
		requireDayEditable(mentor, request.activityDate());
		if (request.menteeId() != null && !access.isMenteeOf(mentor, request.menteeId())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerActivityMenteeUnknown"));
		}
		activity.setActivityDate(request.activityDate());
		activity.setActivityType(request.type());
		activity.setTitle(request.title().trim());
		activity.setDurationMinutes(request.durationMinutes());
		activity.setMenteeId(request.menteeId());
		activity.setDescription(PeerMeetingReportService.trimmed(request.description()));
	}

	private PeerActivity ownActivity(AppUser mentor, UUID activityId) {
		return activityRepository.findByIdAndMentorId(activityId, mentor.getId())
				.orElseThrow(() -> new ResourceNotFoundException("Peer activity not found: " + activityId));
	}

	/** An activity belongs to its day: once the day is sent (or approved) its activities are fixed too. */
	private void requireDayEditable(AppUser mentor, LocalDate date) {
		requireEditable(dailyRepository.findByMentorIdAndReportDate(mentor.getId(), date)
				.map(PeerDailyReport::getStatus)
				.orElse(null));
	}

	private PeerDailyReport newReport(AppUser mentor, LocalDate date) {
		PeerDailyReport report = new PeerDailyReport();
		report.setId(UUID.randomUUID());
		report.setMentorId(mentor.getId());
		report.setProgramId(access.programOf(mentor));
		report.setReportDate(date);
		report.setStatus(PeerReportStatus.DRAFT);
		report.setCreatedAt(calendar.now());
		return report;
	}

	private Map<UUID, String> menteeNames(List<PeerActivity> activities) {
		Set<UUID> ids = activities.stream()
				.map(PeerActivity::getMenteeId)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		return userRepository.findAllById(ids).stream()
				.collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
	}

	private static PeerActivityResponse activityResponse(PeerActivity activity, Map<UUID, String> names) {
		return new PeerActivityResponse(
				activity.getId(),
				activity.getActivityDate(),
				activity.getActivityType(),
				activity.getTitle(),
				activity.getDurationMinutes(),
				activity.getMenteeId(),
				Optional.ofNullable(activity.getMenteeId()).map(names::get).orElse(null),
				activity.getDescription(),
				activity.getCreatedAt());
	}

	private static void requireEditable(PeerReportStatus status) {
		if (status != null && !status.editable()) {
			throw new ConflictException(Texts.forRequest("error.peerReportLocked"));
		}
	}

	private static void requireWorkingDay(LocalDate date) {
		if (!PeerReportCalendar.isWorkingDay(date)) {
			throw new InvalidRequestException(Texts.forRequest("error.peerReportNotWorkingDay"));
		}
	}

	/** A working day that has begun: nobody reports on tomorrow. */
	private void requireReportableDay(LocalDate date) {
		requireWorkingDay(date);
		if (date.isAfter(calendar.today())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerReportFutureDay"));
		}
	}
}
