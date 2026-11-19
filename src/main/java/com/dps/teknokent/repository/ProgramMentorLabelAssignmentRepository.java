package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.ProgramMentorLabelAssignment;

public interface ProgramMentorLabelAssignmentRepository extends JpaRepository<ProgramMentorLabelAssignment, UUID> {

	List<ProgramMentorLabelAssignment> findByProgramId(UUID programId);

	List<ProgramMentorLabelAssignment> findByMentorIdAndProgramIdIn(UUID mentorId, Collection<UUID> programIds);

	// The database cascades these deletes too; doing them here keeps the persistence context (and H2) in step.
	@Modifying(flushAutomatically = true)
	@Query("delete from ProgramMentorLabelAssignment a where a.programId = :programId and a.mentorId = :mentorId")
	int deleteByProgramIdAndMentorId(@Param("programId") UUID programId, @Param("mentorId") UUID mentorId);

	@Modifying(flushAutomatically = true)
	@Query("delete from ProgramMentorLabelAssignment a where a.labelId = :labelId")
	int deleteByLabelId(@Param("labelId") UUID labelId);
}
