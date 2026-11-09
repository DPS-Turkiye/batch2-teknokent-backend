package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.TrainingModule;

public interface TrainingModuleRepository extends JpaRepository<TrainingModule, UUID> {

	Optional<TrainingModule> findByProgramId(UUID programId);
}
