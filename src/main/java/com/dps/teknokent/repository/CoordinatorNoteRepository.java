package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.CoordinatorNote;

public interface CoordinatorNoteRepository extends JpaRepository<CoordinatorNote, UUID> {

	List<CoordinatorNote> findByMentorshipIdOrderByCreatedAtDesc(UUID mentorshipId);
}
