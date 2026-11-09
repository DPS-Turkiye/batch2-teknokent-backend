package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.TrainingAttempt;

public interface TrainingAttemptRepository extends JpaRepository<TrainingAttempt, UUID> {

	List<TrainingAttempt> findByModuleIdAndUserIdAndSubmittedAtAfterOrderBySubmittedAtAsc(UUID moduleId, UUID userId,
			Instant after);

	List<TrainingAttempt> findByModuleIdAndUserIdAndVersionOrderBySubmittedAtDesc(UUID moduleId, UUID userId,
			int version);

	List<TrainingAttempt> findByModuleIdAndVersionAndUserIdIn(UUID moduleId, int version, Collection<UUID> userIds);
}
