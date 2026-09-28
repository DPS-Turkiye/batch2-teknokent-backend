package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
			String status,
			Instant createdAt,
			/** The first coordinator; null when the program has none yet ("Atanmadı"). */
			UUID coordinatorId,
			/** Every coordinator, comma-separated; an admin who created the program is not listed. */
			String coordinatorName,
			int startupCount,
			int acceptedMentorCount,
			int pendingMentorCount,
			List<StartupRow> startups,
			List<MentorMembershipRow> mentors) {
	}

	public record StartupRow(
			UUID id,
			String name,
			String sector,
			UUID programId,
			String programName,
			String founderName,
			String founderJobTitle) {
	}

	public record UserRow(
			UUID id,
			String email,
			String fullName,
			UserRole role,
			UserAccountStatus status,
			UUID organizationId,
			String organizationName,
			String jobTitle,
			Instant createdAt,
			List<String> expertise) {
	}

	public record MentorMembershipRow(
			UUID mentorId,
			String fullName,
			String email,
			String status) {
	}

	public record MentorshipRow(
			UUID id,
			UUID mentorId,
			String mentorName,
			UUID organizationId,
			String organizationName,
			String status,
			LocalDate startDate,
			LocalDate endDate,
			int progressPercent,
			Long daysSinceMeeting,
			int goalDone,
			int goalTotal) {
	}

	public record MeetingMark(
			UUID mentorId,
			UUID organizationId,
			Instant scheduledAt,
			String status) {
	}

	public record RequestRow(
			UUID id,
			UUID organizationId,
			String organizationName,
			UUID mentorId,
			String mentorName,
			String supportArea,
			String status,
			Instant createdAt) {
	}

	public record BottleneckRow(
			UUID id,
			String title,
			String severity,
			String status,
			UUID organizationId,
			String organizationName,
			Instant createdAt) {
	}
}
