package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramEligibleEmail;

public interface ProgramEligibleEmailRepository extends JpaRepository<ProgramEligibleEmail, UUID> {

	List<ProgramEligibleEmail> findByProgramIdOrderByEmailAsc(UUID programId);

	List<ProgramEligibleEmail> findByProgramIdAndEmailIn(UUID programId, Collection<String> emails);

	boolean existsByProgramIdAndEmail(UUID programId, String email);

	Optional<ProgramEligibleEmail> findByIdAndProgramId(UUID id, UUID programId);
}
