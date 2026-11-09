package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.OfficeHoursEnrollment;

public interface OfficeHoursEnrollmentRepository extends JpaRepository<OfficeHoursEnrollment, UUID> {

	Optional<OfficeHoursEnrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);

	/** Adds the enrolment unless it is already there; see {@link OfficeHoursUserRepository#insertIfAbsent}. */
	@Modifying
	@Query(value = """
			INSERT INTO office_hours_enrollment (id, user_id, course_id, role, updated_at)
			VALUES (:id, :userId, :courseId, :role, :now)
			ON CONFLICT (user_id, course_id) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("courseId") UUID courseId,
			@Param("role") String role, @Param("now") Instant now);
}
