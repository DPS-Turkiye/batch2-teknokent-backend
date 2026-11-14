package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.PeerMeetingFormat;
import com.dps.teknokent.domain.PeerMeetingType;
import com.dps.teknokent.domain.PeerReportStatus;

/**
 * A held peer meeting as the reports list it: type, format and duration are the report's when there is one, else
 * what the calendar says. {@code reportStatus} is null while no report was started.
 */
public record PeerReportMeetingResponse(
		UUID meetingId,
		UUID menteeId,
		String menteeName,
		Instant scheduledAt,
		int durationMinutes,
		PeerMeetingType meetingType,
		PeerMeetingFormat meetingFormat,
		PeerReportStatus reportStatus) {
}
