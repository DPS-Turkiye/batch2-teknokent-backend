package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramStartup;

public interface ProgramStartupRepository extends JpaRepository<ProgramStartup, UUID> {
	List<ProgramStartup> findByProgramIdOrderByCreatedAtAsc(UUID programId);

	Optional<ProgramStartup> findByProgramIdAndOrganizationId(UUID programId, UUID organizationId);

	List<ProgramStartup> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

	List<ProgramStartup> findByOrganizationIdIn(Collection<UUID> organizationIds);

	List<ProgramStartup> findByProgramIdIn(Collection<UUID> programIds);

	List<ProgramStartup> findByProgramIdInOrderByCreatedAtAsc(Collection<UUID> programIds);
}
