package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.StartupRound;

public interface StartupRoundRepository extends JpaRepository<StartupRound, UUID> {

	List<StartupRound> findByOrganizationIdOrderByStartsOnDesc(UUID organizationId);

	Optional<StartupRound> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
