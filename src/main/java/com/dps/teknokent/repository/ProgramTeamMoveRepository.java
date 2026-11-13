package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramTeamMove;

public interface ProgramTeamMoveRepository extends JpaRepository<ProgramTeamMove, UUID> {
	List<ProgramTeamMove> findByOrganizationIdOrderByMovedAtDesc(UUID organizationId);
}
