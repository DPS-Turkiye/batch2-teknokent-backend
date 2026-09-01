package com.dps.teknokent.repository;

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
}
