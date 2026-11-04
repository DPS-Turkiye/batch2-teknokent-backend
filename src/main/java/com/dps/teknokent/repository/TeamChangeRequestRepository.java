package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.entity.TeamChangeRequest;

public interface TeamChangeRequestRepository extends JpaRepository<TeamChangeRequest, UUID> {

	List<TeamChangeRequest> findByProgramIdAndStatusOrderByCreatedAtAsc(UUID programId, TeamChangeStatus status);

	List<TeamChangeRequest> findByOrganizationIdAndStatusOrderByCreatedAtAsc(
			UUID organizationId, TeamChangeStatus status);

	boolean existsByMemberIdAndOrganizationIdAndStatus(UUID memberId, UUID organizationId, TeamChangeStatus status);
}
