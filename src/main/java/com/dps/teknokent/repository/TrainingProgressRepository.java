package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.TrainingProgress;

public interface TrainingProgressRepository extends JpaRepository<TrainingProgress, UUID> {

	boolean existsByModuleIdAndUserIdAndVersion(UUID moduleId, UUID userId, int version);

	List<TrainingProgress> findByModuleIdAndVersionAndUserIdIn(UUID moduleId, int version, Collection<UUID> userIds);
}
