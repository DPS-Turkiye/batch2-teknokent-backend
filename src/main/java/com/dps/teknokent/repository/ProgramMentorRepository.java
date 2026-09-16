package com.dps.teknokent.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.entity.ProgramMentor;

public interface ProgramMentorRepository extends JpaRepository<ProgramMentor, UUID> {
	List<ProgramMentor> findByProgramIdOrderByCreatedAtAsc(UUID programId);

	Optional<ProgramMentor> findByProgramIdAndMentorId(UUID programId, UUID mentorId);

	List<ProgramMentor> findByMentorIdAndStatusAndInvitedByOrderByCreatedAtDesc(
			UUID mentorId,
			ProgramMentorStatus status,
			ProgramMentorInvitedBy invitedBy);

	@Query("""
			SELECT pm.mentorId AS mentorId, p.id AS programId, p.name AS programName,
				p.status AS programStatus, pm.role AS role
			FROM ProgramMentor pm, Program p
			WHERE p.id = pm.programId AND pm.mentorId IN :mentorIds AND pm.status = :status
			ORDER BY p.name
			""")
	List<MentorProgramName> findProgramNamesByMentorIds(
			@Param("mentorIds") Collection<UUID> mentorIds,
			@Param("status") ProgramMentorStatus status);
}
