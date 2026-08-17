package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
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
		String goals) {

	public static MentorshipResponse fromEntity(Mentorship mentorship) {
		return new MentorshipResponse(
				mentorship.getId(), mentorship.getMentor().getId(), mentorship.getMentor().getFullName(),
				mentorship.getOrganization().getId(), mentorship.getOrganization().getName(), mentorship.getStatus(),
				mentorship.getStartDate(), mentorship.getEndDate(), mentorship.getGoals());
	}
}
