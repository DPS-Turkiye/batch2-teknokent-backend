package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.ProfileAnalysisStatus;
import com.dps.teknokent.domain.ProfileType;
import com.dps.teknokent.entity.ProfileAnalysis;

public interface ProfileAnalysisRepository extends JpaRepository<ProfileAnalysis, UUID> {
	Optional<ProfileAnalysis> findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(UUID organizationId);

	Optional<ProfileAnalysis> findFirstByAppUserIdOrderByCreatedAtDesc(UUID appUserId);

	Optional<ProfileAnalysis> findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
			UUID organizationId, ProfileType profileType, ProfileAnalysisStatus status);
}
