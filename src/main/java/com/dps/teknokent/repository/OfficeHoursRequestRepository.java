package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.entity.OfficeHoursRequest;

public interface OfficeHoursRequestRepository extends JpaRepository<OfficeHoursRequest, UUID> {

	List<OfficeHoursRequest> findByCourseIdAndStudentIdOrderByCreatedAtDesc(UUID courseId, UUID studentId);

	/** The latest requests sent to the academic, from any course or from Menton. */
	List<OfficeHoursRequest> findTop200ByInstructorIdOrderByCreatedAtDesc(UUID instructorId);

	/** The request, locked until the transaction ends, so two answers to it cannot both pass the status check. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from OfficeHoursRequest request where request.id = :id")
	Optional<OfficeHoursRequest> findForUpdate(@Param("id") UUID id);

	/** The latest requests the student sent, from any course or from Menton. */
	List<OfficeHoursRequest> findTop200ByStudentIdOrderByCreatedAtDesc(UUID studentId);

	List<OfficeHoursRequest> findByCourseIdAndInstructorIdOrderByCreatedAtDesc(UUID courseId, UUID instructorId);

	List<OfficeHoursRequest> findByInstructorIdAndStatusIn(UUID instructorId, Collection<OfficeHoursRequestStatus> statuses);
}
