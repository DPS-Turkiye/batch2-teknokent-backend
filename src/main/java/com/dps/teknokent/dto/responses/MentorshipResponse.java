package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.entity.Mentorship;

public record MentorshipResponse(
		UUID id,
		UUID mentorId,
		String mentorName,
		UUID organizationId,
		String organizationName,
		MentorshipStatus status,
		LocalDate startDate,
		LocalDate endDate,
		int daysPerWeek,
		String goals,
		List<MentorshipGoalResponse> goalItems,
		int progressPercent,
		/** The program the mentorship belongs to; null for a startup outside any program. */
		UUID programId,
		String programName) {

	public static MentorshipResponse fromEntity(Mentorship mentorship) {
		return fromEntity(mentorship, List.of());
	}

	public static MentorshipResponse fromEntity(
			Mentorship mentorship, List<MentorshipGoalResponse> goalItems) {
		int done = (int) goalItems.stream().filter(MentorshipGoalResponse::done).count();
		int total = goalItems.size();
		int progressPercent = total == 0 ? 0 : (int) Math.round((done * 100.0) / total);
		return new MentorshipResponse(
				mentorship.getId(),
				mentorship.getMentor().getId(),
				mentorship.getMentor().getFullName(),
				mentorship.getOrganization().getId(),
				mentorship.getOrganization().getName(),
				mentorship.getStatus(),
				mentorship.getStartDate(),
				mentorship.getEndDate(),
				mentorship.getDaysPerWeek(),
				mentorship.getGoals(),
				goalItems,
				progressPercent,
				mentorship.getProgram() == null ? null : mentorship.getProgram().getId(),
				mentorship.getProgram() == null ? null : mentorship.getProgram().getName());
	}
}
