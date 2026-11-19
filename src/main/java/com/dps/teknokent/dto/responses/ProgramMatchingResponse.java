package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.MatchNeedSource;
import com.dps.teknokent.domain.MentorshipSource;
import com.dps.teknokent.domain.MentorshipStatus;

public final class ProgramMatchingResponse {

	private ProgramMatchingResponse() {
	}

	/** The Eşleşmeler tab: teams waiting for a mentor, and the program's current and past matches. */
	public record Overview(List<WaitingTeam> waiting, List<Match> active, List<Match> past) {
	}

	public record WaitingTeam(
			UUID organizationId,
			String name,
			@Nullable String sector,
			@Nullable String stage,
			@Nullable String leadName,
			int waitingDays,
			MatchNeedSource needSource,
			@Nullable String needTopic,
			@Nullable String needText) {
	}

	/**
	 * A team and its mentor. One working through an accepted request has no mentorship: {@code mentorshipId} is
	 * null, {@code source} says so and {@code requestId} names the request.
	 */
	public record Match(
			@Nullable UUID mentorshipId,
			UUID organizationId,
			String teamName,
			UUID mentorId,
			String mentorName,
			MentorshipStatus status,
			LocalDate startDate,
			@Nullable LocalDate endDate,
			MentorshipSource source,
			@Nullable UUID requestId) {
	}

	/** Ranked suggestions from the program's pool, plus the whole pool for a manual pick. */
	public record Suggestions(@Nullable String method, List<Candidate> recommended, List<Candidate> pool) {
	}

	public record Candidate(
			UUID mentorId,
			String fullName,
			String title,
			List<String> specialties,
			@Nullable String availability,
			int activeTeams,
			int capacity,
			List<String> reasons) {
	}
}
