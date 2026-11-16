package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramSpecialDay;

public interface ProgramSpecialDayRepository extends JpaRepository<ProgramSpecialDay, UUID> {

	List<ProgramSpecialDay> findByProgramIdOrderByDateAsc(UUID programId);

	void deleteByProgramId(UUID programId);
}
