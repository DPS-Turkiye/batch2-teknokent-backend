package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramPeerCategory;

public interface ProgramPeerCategoryRepository extends JpaRepository<ProgramPeerCategory, UUID> {

	List<ProgramPeerCategory> findByProgramId(UUID programId);

	List<ProgramPeerCategory> findByProgramIdIn(Collection<UUID> programIds);
}
