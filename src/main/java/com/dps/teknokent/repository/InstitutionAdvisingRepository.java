package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.InstitutionAdvising;

public interface InstitutionAdvisingRepository extends JpaRepository<InstitutionAdvising, UUID> {

	List<InstitutionAdvising> findByInstitutionAndStudentPersistentId(String institution, String studentPersistentId);

	List<InstitutionAdvising> findByInstitutionAndAdvisorPersistentId(String institution, String advisorPersistentId);

	boolean existsByInstitutionAndStudentPersistentIdAndAdvisorPersistentId(String institution,
			String studentPersistentId, String advisorPersistentId);
}
