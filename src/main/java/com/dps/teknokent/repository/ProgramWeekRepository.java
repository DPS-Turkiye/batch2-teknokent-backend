package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramWeek;

public interface ProgramWeekRepository extends JpaRepository<ProgramWeek, UUID> {

	List<ProgramWeek> findByProgramIdOrderByWeekNumberAsc(UUID programId);

	Optional<ProgramWeek> findByProgramIdAndWeekNumber(UUID programId, int weekNumber);

	boolean existsByProgramId(UUID programId);

	void deleteByProgramId(UUID programId);
}
