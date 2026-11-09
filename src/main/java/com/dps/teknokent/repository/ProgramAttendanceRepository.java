package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramAttendance;

public interface ProgramAttendanceRepository extends JpaRepository<ProgramAttendance, UUID> {

	Optional<ProgramAttendance> findByProgramIdAndUserIdAndWeekNumber(UUID programId, UUID userId, int weekNumber);

	List<ProgramAttendance> findByProgramIdAndWeekNumberBetween(UUID programId, int fromWeek, int toWeek);

	List<ProgramAttendance> findByProgramId(UUID programId);
}
