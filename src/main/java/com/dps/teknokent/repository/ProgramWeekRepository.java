package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramWeek;

public interface ProgramWeekRepository extends JpaRepository<ProgramWeek, UUID> {

	List<ProgramWeek> findByProgramIdOrderByWeekNumberAsc(UUID programId);

	List<ProgramWeek> findByProgramIdIn(Collection<UUID> programIds);

	void deleteByProgramId(UUID programId);
}
