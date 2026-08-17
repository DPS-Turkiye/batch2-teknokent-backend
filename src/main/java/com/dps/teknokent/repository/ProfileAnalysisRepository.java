package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProfileAnalysis;

public interface ProfileAnalysisRepository extends JpaRepository<ProfileAnalysis, UUID> {
	Optional<ProfileAnalysis> findFirstByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	/**
	 * Loads the startup profile used to initialize the mentor-request flow. PostgreSQL serves this
	 * query through the partial {@code idx_profile_analysis_confirmed_startup_latest} index.
	 */
	Optional<ProfileAnalysis> findFirstByOrganizationIdAndProfileTypeAndStatusOrderByCreatedAtDesc(
			UUID organizationId, String profileType, String status);
}
