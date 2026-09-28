package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.AssistantReportGeneration;

public interface AssistantReportGenerationRepository extends JpaRepository<AssistantReportGeneration, UUID> {
  long countByActorIdAndCreatedAtAfter(UUID actorId, Instant after);
}
