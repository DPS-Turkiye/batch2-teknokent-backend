package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProfileAnalysis;

public interface ProfileAnalysisRepository extends JpaRepository<ProfileAnalysis, UUID> {
	Optional<ProfileAnalysis> findFirstByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	Optional<ProfileAnalysis> findFirstByOrganizationIdAndProfileTypeAndStatusOrderByCreatedAtDesc(
			UUID organizationId, String profileType, String status);
}
