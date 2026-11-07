package com.dps.teknokent.dto.requests;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What a student mentor (İŞKUR) tells mentees about their studies. Lists are replaced as sent. */
public record StudentProfileRequest(
		@Size(max = 255) String department,
		@Size(max = 32) String studyYear,
		@Size(max = 10) List<@NotBlank @Size(max = 255) String> strongCourses,
		@Size(max = 10) List<@NotBlank @Size(max = 500) String> projects,
		@Size(max = 500) String erasmus) {
}
