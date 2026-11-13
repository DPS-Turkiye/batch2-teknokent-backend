package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramMentorRemoval;

public interface ProgramMentorRemovalRepository extends JpaRepository<ProgramMentorRemoval, UUID> {
	List<ProgramMentorRemoval> findByMentorIdOrderByRemovedAtDesc(UUID mentorId);
}
