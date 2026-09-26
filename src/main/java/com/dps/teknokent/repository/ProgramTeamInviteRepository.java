package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramTeamInvite;

public interface ProgramTeamInviteRepository extends JpaRepository<ProgramTeamInvite, UUID> {

	Optional<ProgramTeamInvite> findByCode(String code);

	boolean existsByCode(String code);

	List<ProgramTeamInvite> findByProgramIdOrderByCreatedAtAsc(UUID programId);

	Optional<ProgramTeamInvite> findByIdAndProgramId(UUID id, UUID programId);

	boolean existsByProgramIdAndLeadEmailIgnoreCase(UUID programId, String leadEmail);
}
