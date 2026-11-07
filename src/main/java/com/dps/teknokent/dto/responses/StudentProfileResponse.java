package com.dps.teknokent.dto.responses;

import java.util.List;

import com.dps.teknokent.entity.MentorProfile;

/** A student mentor's studies, shown to mentees; absent for mentors who are not students. */
public record StudentProfileResponse(
		String department,
		String studyYear,
		List<String> strongCourses,
		List<String> projects,
		String erasmus) {

	public static StudentProfileResponse fromEntity(MentorProfile profile) {
		boolean empty = profile.getDepartment() == null && profile.getStudyYear() == null
				&& profile.getErasmus() == null && profile.getStrongCourses().isEmpty()
				&& profile.getProjects().isEmpty();
		if (empty) {
			return null;
		}
		return new StudentProfileResponse(profile.getDepartment(), profile.getStudyYear(),
				List.copyOf(profile.getStrongCourses()), List.copyOf(profile.getProjects()), profile.getErasmus());
	}
}
