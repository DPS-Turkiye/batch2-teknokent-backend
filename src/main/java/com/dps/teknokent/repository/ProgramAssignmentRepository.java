package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramAssignment;

public interface ProgramAssignmentRepository extends JpaRepository<ProgramAssignment, UUID> {

	@EntityGraph(attributePaths = "createdBy")
	List<ProgramAssignment> findByProgramIdOrderByCreatedAtDesc(UUID programId);

	List<ProgramAssignment> findByProgramIdIn(Collection<UUID> programIds);
}
