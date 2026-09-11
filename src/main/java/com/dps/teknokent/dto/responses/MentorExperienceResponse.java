package com.dps.teknokent.dto.responses;

import com.dps.teknokent.entity.MentorExperience;

public record MentorExperienceResponse(String title, String organization, String period, String summary) {

	public static MentorExperienceResponse fromEntity(MentorExperience experience) {
		return new MentorExperienceResponse(experience.getTitle(), experience.getOrganization(),
				experience.getPeriod(), experience.getSummary());
	}
}
