package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgressUpdate;

public interface ProgressUpdateRepository extends JpaRepository<ProgressUpdate, UUID> {

	List<ProgressUpdate> findByOrganizationIdOrderByRecordedAtDesc(UUID organizationId);
}
