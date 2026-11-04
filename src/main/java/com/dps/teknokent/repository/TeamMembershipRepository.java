package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.TeamMembership;

public interface TeamMembershipRepository extends JpaRepository<TeamMembership, UUID> {

	List<TeamMembership> findByUserIdOrderByJoinedAtDesc(UUID userId);

	Optional<TeamMembership> findFirstByUserIdAndOrganizationIdAndLeftAtIsNullOrderByJoinedAtDesc(
			UUID userId, UUID organizationId);
}
