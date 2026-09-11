package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorshipGoal;

public interface MentorshipGoalRepository extends JpaRepository<MentorshipGoal, UUID> {

	List<MentorshipGoal> findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(UUID mentorshipId);

	long countByMentorshipId(UUID mentorshipId);
}
