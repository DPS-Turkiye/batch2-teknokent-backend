package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;

import com.dps.teknokent.domain.PeerReportStatus;

/** The five day cards of a week ("Pzt · 9 Kasım ... Onaylandı") and how many daily reports were sent. */
public record PeerReportWeekResponse(LocalDate weekStart, List<Day> days, int dailySent) {

	/** {@code status} is null for a day nobody started; {@code missingMeetingReports} makes it "Eksik Bilgi". */
	public record Day(LocalDate date, PeerReportStatus status, int meetings, int activities,
			int missingMeetingReports) {
	}
}
