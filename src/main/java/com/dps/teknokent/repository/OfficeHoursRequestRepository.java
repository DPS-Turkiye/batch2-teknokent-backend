package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.entity.OfficeHoursRequest;

public interface OfficeHoursRequestRepository extends JpaRepository<OfficeHoursRequest, UUID> {

	List<OfficeHoursRequest> findByCourseIdAndStudentIdOrderByCreatedAtDesc(UUID courseId, UUID studentId);

	/** Every request sent to the academic, from any course or from Menton. */
	List<OfficeHoursRequest> findByInstructorIdOrderByCreatedAtDesc(UUID instructorId);

	/** Every request the student sent, from any course or from Menton. */
	List<OfficeHoursRequest> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

	List<OfficeHoursRequest> findByCourseIdAndInstructorIdOrderByCreatedAtDesc(UUID courseId, UUID instructorId);

	List<OfficeHoursRequest> findByInstructorIdAndStatusIn(UUID instructorId, Collection<OfficeHoursRequestStatus> statuses);
}
