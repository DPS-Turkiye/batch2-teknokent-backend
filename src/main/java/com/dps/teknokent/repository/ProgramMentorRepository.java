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

	List<ProgramMentor> findByMentorIdAndStatusOrderByCreatedAtDesc(
			UUID mentorId,
			ProgramMentorStatus status);

	@Query("""
			SELECT COUNT(pm)
			FROM ProgramMentor pm, ProgramStartup ps
			WHERE pm.programId = ps.programId
				AND pm.mentorId = :mentorId
				AND ps.organizationId = :organizationId
				AND pm.status = :status
			""")
	long countAcceptedForOrganization(
			@Param("mentorId") UUID mentorId,
			@Param("organizationId") UUID organizationId,
			@Param("status") ProgramMentorStatus status);

	@Query("""
			SELECT pm.mentorId AS mentorId, p.name AS programName
			FROM ProgramMentor pm, Program p
			WHERE p.id = pm.programId AND pm.mentorId IN :mentorIds AND pm.status = :status
			ORDER BY p.name
			""")
	List<MentorProgramName> findProgramNamesByMentorIds(
			@Param("mentorIds") Collection<UUID> mentorIds,
			@Param("status") ProgramMentorStatus status);
}
