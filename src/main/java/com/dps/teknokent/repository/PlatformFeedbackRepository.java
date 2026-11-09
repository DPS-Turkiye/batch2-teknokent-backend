package com.dps.teknokent.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.entity.PlatformFeedback;

public interface PlatformFeedbackRepository extends JpaRepository<PlatformFeedback, UUID> {

	@EntityGraph(attributePaths = { "author", "program" })
	Page<PlatformFeedback> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

	@EntityGraph(attributePaths = { "author", "program" })
	Page<PlatformFeedback> findByReadAtIsNullOrderByCreatedAtDescIdDesc(Pageable pageable);

	long countByReadAtIsNull();

	@EntityGraph(attributePaths = { "author", "program" })
	Page<PlatformFeedback> findByProgramTypeOrderByCreatedAtDescIdDesc(ProgramType programType, Pageable pageable);

	@EntityGraph(attributePaths = { "author", "program" })
	Page<PlatformFeedback> findByReadAtIsNullAndProgramTypeOrderByCreatedAtDescIdDesc(ProgramType programType,
			Pageable pageable);
}
