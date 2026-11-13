package com.dps.teknokent.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.PeerActivityType;
import com.dps.teknokent.domain.PeerMeetingType;
import com.dps.teknokent.domain.PeerMentorSkill;
import com.dps.teknokent.domain.PeerReportStatus;
import com.dps.teknokent.dto.requests.SavePeerWeeklyReportRequest;
import com.dps.teknokent.dto.responses.PeerReportWeekResponse;
import com.dps.teknokent.dto.responses.PeerWeeklyReportResponse;
import com.dps.teknokent.dto.responses.PeerWeeklySummary;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.PeerActivity;
import com.dps.teknokent.entity.PeerMeetingReport;
import com.dps.teknokent.entity.PeerWeeklyReport;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.PeerWeeklyReportRepository;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Haftalık Özet": assembled from the week's meeting reports, activities and daily reports when it is read (there is
 * no job), plus three answers of the mentor's. It can be sent from Friday evening; the summary is frozen then.
 */
@Service
@RequiredArgsConstructor
public class PeerWeeklyReportService {

	private final PeerReportAccess access;
	private final PeerReportCalendar calendar;
	private final PeerMeetingReportService meetingReports;
	private final PeerDailyReportService dailyReports;
	private final PeerWeeklyReportRepository weeklyRepository;
	private final JsonMapper jsonMapper;

	@Transactional(readOnly = true)
	public PeerWeeklyReportResponse get(UUID actorId, LocalDate weekStart) {
		AppUser mentor = access.requireMentor(actorId);
		PeerDailyReportService.requireMonday(weekStart);
		return response(mentor, weekStart,
				weeklyRepository.findByMentorIdAndWeekStart(mentor.getId(), weekStart).orElse(null));
	}

	@Transactional
	public PeerWeeklyReportResponse save(UUID actorId, LocalDate weekStart, SavePeerWeeklyReportRequest request) {
		AppUser mentor = access.requireMentor(actorId);
		PeerDailyReportService.requireMonday(weekStart);
		if (weekStart.isAfter(calendar.today())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerReportFutureDay"));
		}
		PeerWeeklyReport report = weeklyRepository.findByMentorIdAndWeekStart(mentor.getId(), weekStart)
				.orElseGet(() -> newReport(mentor, weekStart));
		requireEditable(report);
		report.setNeedsObserved(PeerMeetingReportService.trimmed(request.needsObserved()));
		report.setGrowth(PeerMeetingReportService.trimmed(request.growth()));
		report.setNextWeekPlan(PeerMeetingReportService.trimmed(request.nextWeekPlan()));
		report.setUpdatedAt(calendar.now());
		return response(mentor, weekStart,
				PeerReportAccess.savedOrConflict(() -> weeklyRepository.saveAndFlush(report)));
	}

	/**
	 * Sends the week to the coordinator. Refused before Friday evening, while a held meeting lacks a sent report or a
	 * day with mentoring work lacks a sent daily report, and while one of the three answers is empty.
	 */
	@Transactional
	public PeerWeeklyReportResponse submit(UUID actorId, LocalDate weekStart) {
		AppUser mentor = access.requireMentor(actorId);
		PeerDailyReportService.requireMonday(weekStart);
		PeerWeeklyReport report = weeklyRepository.findByMentorIdAndWeekStart(mentor.getId(), weekStart)
				.orElseThrow(() -> new ConflictException(Texts.forRequest("error.peerReportNotStarted")));
		requireEditable(report);
		if (calendar.now().isBefore(calendar.weeklyOpensAt(weekStart))) {
			throw new ConflictException(Texts.forRequest("error.peerWeeklyNotOpen"));
		}
		if (access.meetingsAhead(mentor, calendar.startOf(weekStart.plusDays(7)))) {
			throw new ConflictException(Texts.forRequest("error.peerWeeklyMeetingsAhead"));
		}
		PeerWeeklySummary summary = summary(mentor, weekStart);
		if (summary.meetingReportsSent() < summary.meetingReportsTotal()) {
			throw new ConflictException(Texts.forRequest("error.peerWeeklyMissingMeetingReports"));
		}
		List<PeerReportWeekResponse.Day> days = dailyReports.days(mentor, weekStart);
		if (dailySent(days) < dailyRequired(days)) {
			throw new ConflictException(Texts.forRequest("error.peerWeeklyMissingDailyReports"));
		}
		if (PeerMeetingReportService.blank(report.getNeedsObserved())
				|| PeerMeetingReportService.blank(report.getGrowth())
				|| PeerMeetingReportService.blank(report.getNextWeekPlan())) {
			throw new InvalidRequestException(Texts.forRequest("error.peerReportIncomplete", "EVALUATION"),
					"PEER_REPORT_INCOMPLETE");
		}
		report.setSummarySnapshot(jsonMapper.writeValueAsString(summary));
		report.submit(calendar.now());
		return response(mentor, weekStart,
				PeerReportAccess.savedOrConflict(() -> weeklyRepository.saveAndFlush(report)));
	}

	/** The week's numbers, Monday to Sunday, from the held meetings, their reports and the activities. */
	PeerWeeklySummary summary(AppUser mentor, LocalDate weekStart) {
		List<Meeting> meetings = access.heldMeetings(mentor, calendar.startOf(weekStart),
				calendar.startOf(weekStart.plusDays(7)));
		Map<UUID, PeerMeetingReport> reports = meetingReports.reportsFor(mentor, meetings);
		Map<UUID, PeerMeetingType> types = meetingReports.types(mentor, meetings, reports);
		List<PeerActivity> activities = dailyReports.activities(mentor, weekStart, weekStart.plusDays(6));

		int first = 0;
		int recorded = activities.stream().mapToInt(PeerActivity::getDurationMinutes).sum();
		Map<PeerMentorSkill, Integer> skillCounts = new EnumMap<>(PeerMentorSkill.class);
		Map<UUID, MenteeTally> byMentee = new LinkedHashMap<>();
		for (Meeting meeting : meetings) {
			PeerMeetingReport report = reports.get(meeting.getId());
			if (types.get(meeting.getId()) == PeerMeetingType.FIRST) {
				first++;
			}
			recorded += PeerMeetingReportService.durationOf(meeting, report);
			AppUser mentee = meeting.getMentorshipRequest().getRequester();
			MenteeTally tally = byMentee.computeIfAbsent(mentee.getId(), id -> new MenteeTally(mentee));
			tally.meetings++;
			if (report != null) {
				report.getSkills().forEach(skill -> skillCounts.merge(skill, 1, Integer::sum));
				tally.add(meeting, report);
			}
		}
		int sent = (int) meetings.stream()
				.filter(meeting -> PeerMeetingReportService.sent(reports.get(meeting.getId())))
				.count();
		return new PeerWeeklySummary(
				meetings.size(),
				first,
				meetings.size() - first,
				byMentee.values().stream()
						.map(tally -> new PeerWeeklySummary.MenteeRef(tally.mentee.getId(), tally.mentee.getFullName()))
						.toList(),
				sent,
				meetings.size(),
				activities.size(),
				activities.stream().map(PeerActivity::getActivityType).distinct()
						.sorted(Comparator.comparing(PeerActivityType::ordinal)).toList(),
				recorded,
				skillCounts.entrySet().stream()
						.sorted(Map.Entry.<PeerMentorSkill, Integer>comparingByValue().reversed()
								.thenComparing(Map.Entry.comparingByKey()))
						.map(entry -> new PeerWeeklySummary.SkillCount(entry.getKey(), entry.getValue()))
						.toList(),
				byMentee.values().stream().map(MenteeTally::outcome).toList());
	}

	PeerWeeklyReportResponse response(AppUser mentor, LocalDate weekStart, PeerWeeklyReport report) {
		PeerReportStatus status = report == null ? null : report.getStatus();
		boolean open = status == null || status.editable();
		PeerWeeklySummary summary = !open && report.getSummarySnapshot() != null
				? jsonMapper.readValue(report.getSummarySnapshot(), PeerWeeklySummary.class)
				: summary(mentor, weekStart);
		List<PeerReportWeekResponse.Day> days = dailyReports.days(mentor, weekStart);
		return new PeerWeeklyReportResponse(
				weekStart,
				report == null ? null : report.getId(),
				status,
				open && !weekStart.isAfter(calendar.today()),
				calendar.weeklyOpensAt(weekStart),
				calendar.weeklyDeadline(weekStart),
				summary,
				days,
				dailyRequired(days),
				dailySent(days),
				report == null ? null : report.getNeedsObserved(),
				report == null ? null : report.getGrowth(),
				report == null ? null : report.getNextWeekPlan(),
				report == null ? null : report.getSubmittedAt(),
				report == null ? null : report.getRevisionNote(),
				report == null ? null : report.getUpdatedAt());
	}

	/** A day needs its own report once it had mentoring work: a held meeting or an activity. */
	private static int dailyRequired(List<PeerReportWeekResponse.Day> days) {
		return (int) days.stream().filter(day -> day.meetings() > 0 || day.activities() > 0).count();
	}

	private static int dailySent(List<PeerReportWeekResponse.Day> days) {
		return (int) days.stream()
				.filter(day -> day.meetings() > 0 || day.activities() > 0)
				.filter(day -> day.status() != null && day.status().sent())
				.count();
	}

	private PeerWeeklyReport newReport(AppUser mentor, LocalDate weekStart) {
		PeerWeeklyReport report = new PeerWeeklyReport();
		report.setId(UUID.randomUUID());
		report.setMentorId(mentor.getId());
		report.setProgramId(access.programOf(mentor));
		report.setWeekStart(weekStart);
		report.setStatus(PeerReportStatus.DRAFT);
		report.setCreatedAt(calendar.now());
		return report;
	}

	private static void requireEditable(PeerWeeklyReport report) {
		if (!report.getStatus().editable()) {
			throw new ConflictException(Texts.forRequest("error.peerReportLocked"));
		}
	}

	/** One mentee's line under "Görüşme çıktıları". */
	private static final class MenteeTally {

		private final AppUser mentee;
		private final List<PeerWeeklySummary.ReportRef> reports = new ArrayList<>();
		private int meetings;
		private int actionsDone;
		private int actionsTotal;

		private MenteeTally(AppUser mentee) {
			this.mentee = mentee;
		}

		private void add(Meeting meeting, PeerMeetingReport report) {
			reports.add(new PeerWeeklySummary.ReportRef(meeting.getId(), meeting.getScheduledAt(), report.getOutcome()));
			actionsTotal += report.getActions().size();
			actionsDone += (int) report.getActions().stream().filter(action -> action.getDoneAt() != null).count();
		}

		private PeerWeeklySummary.MenteeOutcome outcome() {
			return new PeerWeeklySummary.MenteeOutcome(mentee.getId(), mentee.getFullName(), meetings,
					List.copyOf(reports), actionsDone, actionsTotal);
		}
	}
}
