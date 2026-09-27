package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MatchNeedSource;
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
			String sector,
			String stage,
			String leadName,
			int waitingDays,
			MatchNeedSource needSource,
			String needTopic,
			String needText) {
	}

	public record Match(
			UUID mentorshipId,
			UUID organizationId,
			String teamName,
			UUID mentorId,
			String mentorName,
			MentorshipStatus status,
			LocalDate startDate,
			LocalDate endDate) {
	}

	/** Ranked suggestions from the program's pool, plus the whole pool for a manual pick. */
	public record Suggestions(String method, List<Candidate> recommended, List<Candidate> pool) {
	}

	public record Candidate(
			UUID mentorId,
			String fullName,
			String title,
			List<String> specialties,
			String availability,
			int activeTeams,
			int capacity,
			List<String> reasons) {
	}
}
