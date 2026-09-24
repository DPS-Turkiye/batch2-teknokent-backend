package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramPhase;

public interface ProgramPhaseRepository extends JpaRepository<ProgramPhase, UUID> {
	List<ProgramPhase> findByProgramIdOrderByPositionAsc(UUID programId);

	List<ProgramPhase> findByProgramIdInOrderByPositionAsc(Collection<UUID> programIds);

	void deleteByProgramId(UUID programId);
}
