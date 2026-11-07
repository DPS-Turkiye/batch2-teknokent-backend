package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.entity.OfficeHoursEnrollment;

public interface OfficeHoursEnrollmentRepository extends JpaRepository<OfficeHoursEnrollment, UUID> {

	Optional<OfficeHoursEnrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);

	boolean existsByUserIdAndRole(UUID userId, OfficeHoursRole role);

	List<OfficeHoursEnrollment> findByCourseIdAndRole(UUID courseId, OfficeHoursRole role);
}
