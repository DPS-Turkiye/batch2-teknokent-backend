package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramCoordinator;

public interface ProgramCoordinatorRepository extends JpaRepository<ProgramCoordinator, UUID> {

	boolean existsByProgramIdAndUserId(UUID programId, UUID userId);

	Optional<ProgramCoordinator> findByProgramIdAndUserId(UUID programId, UUID userId);

	List<ProgramCoordinator> findByProgramIdOrderByCreatedAtAsc(UUID programId);

	List<ProgramCoordinator> findByProgramIdIn(Collection<UUID> programIds);

	List<ProgramCoordinator> findByUserId(UUID userId);
}
