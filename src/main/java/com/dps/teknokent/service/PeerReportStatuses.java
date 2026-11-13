package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.PeerReportStatus;
import com.dps.teknokent.entity.PeerWeeklyReport;
import com.dps.teknokent.repository.PeerWeeklyReportRepository;

import lombok.RequiredArgsConstructor;

/**
 * The status a report shows, which can be more than the stored one. Only the weekly summary is reviewed, and its
 * approval covers the week's daily and meeting reports. A summary nobody decided on within seven days of being sent
 * counts as approved. Both are worked out when a report is read, never written by a timer: the stored row keeps
 * IN_REVIEW (with no reviewer), and a coordinator's decision on it comes too late (409).
 */
@Component
@RequiredArgsConstructor
class PeerReportStatuses {

	static final Duration AUTO_APPROVE_AFTER = Duration.ofDays(7);

	private final PeerWeeklyReportRepository weeklyRepository;
	private final PeerReportCalendar calendar;

	/** A weekly summary's status: in review for seven days, it reads as approved. */
	PeerReportStatus ofWeek(PeerReportStatus stored, Instant submittedAt) {
		if (stored == PeerReportStatus.IN_REVIEW && submittedAt != null
				&& !calendar.now().isBefore(submittedAt.plus(AUTO_APPROVE_AFTER))) {
			return PeerReportStatus.APPROVED;
		}
		return stored;
	}

	PeerReportStatus ofWeek(PeerWeeklyReport week) {
		return ofWeek(week.getStatus(), week.getSubmittedAt());
	}

	/** One read's lookups, so a list of reports from the same weeks asks for each week once. */
	Lookup lookup() {
		return new Lookup();
	}

	final class Lookup {

		private final Map<String, Boolean> approvedWeeks = new HashMap<>();

		/** A daily or meeting report's status: a sent one reads as approved once its week is. */
		PeerReportStatus of(PeerReportStatus stored, UUID mentorId, LocalDate day) {
			if (stored != PeerReportStatus.IN_REVIEW) {
				return stored;
			}
			LocalDate weekStart = calendar.weekStartOf(day);
			boolean approved = approvedWeeks.computeIfAbsent(mentorId + "/" + weekStart,
					key -> weeklyRepository.findByMentorIdAndWeekStart(mentorId, weekStart)
							.map(week -> ofWeek(week) == PeerReportStatus.APPROVED)
							.orElse(false));
			return approved ? PeerReportStatus.APPROVED : stored;
		}
	}
}
