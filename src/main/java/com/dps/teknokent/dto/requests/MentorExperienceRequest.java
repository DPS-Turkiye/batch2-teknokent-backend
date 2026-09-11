package com.dps.teknokent.dto.requests;

import com.dps.teknokent.entity.MentorExperience;
import com.dps.teknokent.util.TextUtils;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MentorExperienceRequest(
		@NotBlank @Size(max = 200) String title,
		@Size(max = 200) String organization,
		@Size(max = 100) String period,
		@Size(max = 300) String summary) {

	public MentorExperience toEntity() {
		return new MentorExperience(TextUtils.trim(title), TextUtils.normalizeOptional(organization),
				TextUtils.normalizeOptional(period), TextUtils.normalizeOptional(summary));
	}
}
