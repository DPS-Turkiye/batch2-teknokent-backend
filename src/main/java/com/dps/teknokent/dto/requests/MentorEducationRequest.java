package com.dps.teknokent.dto.requests;

import com.dps.teknokent.entity.MentorEducation;
import com.dps.teknokent.util.TextUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** degree carries the department too, e.g. "Lisans, Bilgisayar Mühendisliği". */
public record MentorEducationRequest(
		@NotBlank @Size(max = 200) String school,
		@Size(max = 200) String degree,
		@Size(max = 100) String period) {
	public MentorEducation toEntity() {
		return new MentorEducation(TextUtils.trim(school), TextUtils.normalizeOptional(degree),
				TextUtils.normalizeOptional(period));
	}
}
