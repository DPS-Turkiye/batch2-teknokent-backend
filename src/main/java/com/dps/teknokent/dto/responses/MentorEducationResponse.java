package com.dps.teknokent.dto.responses;

import com.dps.teknokent.entity.MentorEducation;

public record MentorEducationResponse(String school, String degree, String period) {
	public static MentorEducationResponse fromEntity(MentorEducation education) {
		return new MentorEducationResponse(education.getSchool(), education.getDegree(), education.getPeriod());
	}
}
