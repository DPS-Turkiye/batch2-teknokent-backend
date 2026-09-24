package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramEvent;

public interface ProgramEventRepository extends JpaRepository<ProgramEvent, UUID> {
	List<ProgramEvent> findByProgramIdOrderByStartsAtAsc(UUID programId);

	List<ProgramEvent> findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(
			Collection<UUID> programIds,
			Instant from);

	Optional<ProgramEvent> findByIdAndProgramId(UUID id, UUID programId);
}
