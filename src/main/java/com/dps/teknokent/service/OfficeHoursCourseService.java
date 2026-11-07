package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.responses.OfficeHoursCourseResponse;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursEnrollment;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
import com.dps.teknokent.repository.OfficeHoursEnrollmentRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OfficeHoursCourseService {

	private final OfficeHoursUserRepository userRepository;
	private final OfficeHoursCourseRepository courseRepository;
	private final OfficeHoursEnrollmentRepository enrollmentRepository;

	/** The course as this person sees it; a course they never launched from does not exist for them. */
	@Transactional(readOnly = true)
	public OfficeHoursCourseResponse courseFor(UUID userId, UUID courseId) {
		OfficeHoursEnrollment enrollment = enrollmentRepository.findByUserIdAndCourseId(userId, courseId)
				.orElseThrow(() -> new ResourceNotFoundException("Course not found."));
		OfficeHoursCourse course = courseRepository.findById(courseId)
				.orElseThrow(() -> new ResourceNotFoundException("Course not found."));
		OfficeHoursUser user = userRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("Course not found."));
		return new OfficeHoursCourseResponse(course.getId(), course.getTitle(), course.getShortName(),
				enrollment.getRole(), user.getFullName());
	}
}
