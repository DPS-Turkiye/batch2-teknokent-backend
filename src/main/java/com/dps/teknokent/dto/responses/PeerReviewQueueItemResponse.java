package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.PeerReportKind;
import com.dps.teknokent.domain.PeerReportStatus;

/**
 * One sent report in a coordinator's review list. {@code day} is the meeting's day, the report's day or the week's
 * Monday. The mentee and the support flag are for meeting reports only.
 */
public record PeerReviewQueueItemResponse(
		PeerReportKind kind,
		UUID id,
		UUID programId,
		UUID mentorId,
		@Nullable String mentorName,
		LocalDate day,
		@Nullable UUID menteeId,
		@Nullable String menteeName,
		@Nullable Boolean coordinatorSupportNeeded,
		PeerReportStatus status,
		Instant submittedAt) {
}
