package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.entity.Program;

public interface ProgramRepository extends JpaRepository<Program, UUID> {
	List<Program> findByCreatedByOrderByCreatedAtDesc(UUID createdBy);

	List<Program> findAllByOrderByCreatedAtDesc();

	List<Program> findByProgramType(ProgramType programType);

	List<Program> findBySyllabusDocumentId(UUID syllabusDocumentId);
}
