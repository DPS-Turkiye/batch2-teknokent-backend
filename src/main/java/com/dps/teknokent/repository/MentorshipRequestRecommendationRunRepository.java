package com.dps.teknokent.repository;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorshipRequestRecommendationRun;

public interface MentorshipRequestRecommendationRunRepository
		extends JpaRepository<MentorshipRequestRecommendationRun, UUID> {
	Optional<MentorshipRequestRecommendationRun> findByRequestId(UUID requestId);
}
