package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;

public record AdminOverviewResponse(
		List<ProgramRow> programs,
		List<UserRow> users,
		List<MentorshipRow> mentorships,
		List<RequestRow> requests,
		List<BottleneckRow> bottlenecks,
		long meetingsLast8Weeks,
		List<MeetingMark> meetings) {

	public record ProgramRow(
			UUID id,
			String name,
			ProgramStatus status,
			ProgramType type,
			Instant createdAt,
			/* The first coordinator; null when the program has none yet ("Atanmadı"). */
			@Nullable UUID coordinatorId,
			/* Every coordinator, comma-separated; an admin who created the program is not listed. */
			@Nullable String coordinatorName,
			/* Every coordinator's id, in {@code coordinatorName} order; empty when the program has none. */
			List<UUID> coordinatorIds,
			int startupCount,
			int acceptedMentorCount,
			int pendingMentorCount,
			List<StartupRow> startups,
			List<MentorMembershipRow> mentors) {
	}

	public record StartupRow(
			UUID id,
			String name,
			@Nullable String sector,
			UUID programId,
			String programName,
			@Nullable String founderName,
			@Nullable String founderJobTitle) {
	}

	public record UserRow(
			UUID id,
			String email,
			String fullName,
			UserRole role,
			/* Every active permission, the primary role included; a second-role mentor is listed with the mentors. */
			List<UserRole> roles,
			UserAccountStatus status,
			@Nullable UUID organizationId,
			String organizationName,
			String jobTitle,
			Instant createdAt,
			List<String> expertise,
			/* Coordinators only: false until they set a password from the e-mailed link; null when unknown. */
			@Nullable Boolean passwordSet) {
	}

	public record MentorMembershipRow(
			UUID mentorId,
			String fullName,
			String email,
			ProgramMentorStatus status) {
	}

	public record MentorshipRow(
			UUID id,
			UUID mentorId,
			String mentorName,
			UUID organizationId,
			String organizationName,
			MentorshipStatus status,
			LocalDate startDate,
			LocalDate endDate,
			int progressPercent,
			@Nullable Long daysSinceMeeting,
			int goalDone,
			int goalTotal,
			/* The program the mentorship was made in; null for one made before V82. */
			@Nullable UUID programId) {
	}

	public record MeetingMark(
			UUID mentorId,
			UUID organizationId,
			Instant scheduledAt,
			MeetingStatus status) {
	}

	public record RequestRow(
			UUID id,
			UUID organizationId,
			String organizationName,
			UUID mentorId,
			String mentorName,
			String supportArea,
			MentorshipRequestStatus status,
			Instant createdAt) {
	}

	public record BottleneckRow(
			UUID id,
			String title,
			BottleneckSeverity severity,
			BottleneckStatus status,
			UUID organizationId,
			String organizationName,
			Instant createdAt) {
	}
}
