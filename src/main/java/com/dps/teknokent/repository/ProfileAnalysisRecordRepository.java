package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.dps.teknokent.domain.ProfileType;
import com.dps.teknokent.entity.ProfileAnalysis;

public interface ProfileAnalysisRecordRepository extends Repository<ProfileAnalysis, UUID> {
	Optional<ProfileAnalysis> findById(UUID id);

	Optional<ProfileAnalysis> findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeOrderByCreatedAtDescIdDesc(
			UUID organizationId, ProfileType profileType);

	Optional<ProfileAnalysis> findFirstByOrganizationIdAndAppUserIdAndProfileTypeOrderByCreatedAtDescIdDesc(
			UUID organizationId, UUID appUserId, ProfileType profileType);
}
