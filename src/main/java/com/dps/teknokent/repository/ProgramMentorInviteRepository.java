package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramMentorInvite;

public interface ProgramMentorInviteRepository extends JpaRepository<ProgramMentorInvite, UUID> {

	Optional<ProgramMentorInvite> findByCode(String code);

	List<ProgramMentorInvite> findByProgramIdOrderByCreatedAtAsc(UUID programId);

	Optional<ProgramMentorInvite> findByIdAndProgramId(UUID id, UUID programId);

	Optional<ProgramMentorInvite> findByProgramIdAndEmailIgnoreCase(UUID programId, String email);

	Optional<ProgramMentorInvite> findByProgramIdAndMentorId(UUID programId, UUID mentorId);
}
