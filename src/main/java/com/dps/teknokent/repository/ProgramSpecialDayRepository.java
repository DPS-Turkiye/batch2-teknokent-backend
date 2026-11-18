package com.dps.teknokent.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramSpecialDay;

public interface ProgramSpecialDayRepository extends JpaRepository<ProgramSpecialDay, UUID> {

	List<ProgramSpecialDay> findByProgramIdOrderByDateAsc(UUID programId);

	List<ProgramSpecialDay> findByProgramIdInAndDateBetweenOrderByDateAsc(Collection<UUID> programIds, LocalDate from,
			LocalDate to);

	void deleteByProgramId(UUID programId);
}
