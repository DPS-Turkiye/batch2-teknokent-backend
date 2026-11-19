package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.entity.Program;

import jakarta.persistence.LockModeType;

public interface ProgramRepository extends JpaRepository<Program, UUID> {
	List<Program> findByCreatedByOrderByCreatedAtDesc(UUID createdBy);

	List<Program> findAllByOrderByCreatedAtDesc();

	List<Program> findByProgramType(ProgramType programType);

	List<Program> findBySyllabusDocumentId(UUID syllabusDocumentId);

	/** The row locked until the transaction ends, so changes to one program's mentor labels run one at a time. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Program p where p.id = :id")
	Optional<Program> findByIdForUpdate(@Param("id") UUID id);
}
