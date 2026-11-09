package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.OfficeHoursException;

public interface OfficeHoursExceptionRepository extends JpaRepository<OfficeHoursException, UUID> {

	List<OfficeHoursException> findByInstructorIdOrderByStartsOnAsc(UUID instructorId);
}
