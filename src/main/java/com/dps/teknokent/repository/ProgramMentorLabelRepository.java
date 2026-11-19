package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramMentorLabel;

public interface ProgramMentorLabelRepository extends JpaRepository<ProgramMentorLabel, UUID> {

	List<ProgramMentorLabel> findByProgramIdOrderByNameAsc(UUID programId);

	List<ProgramMentorLabel> findByProgramIdInOrderByNameAsc(Collection<UUID> programIds);

	Optional<ProgramMentorLabel> findByIdAndProgramId(UUID id, UUID programId);

	long countByProgramId(UUID programId);
}
