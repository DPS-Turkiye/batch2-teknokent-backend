package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.dps.teknokent.entity.InstitutionAdvising;

public interface InstitutionAdvisingRepository extends JpaRepository<InstitutionAdvising, UUID> {

	List<InstitutionAdvising> findByInstitutionAndStudentPersistentId(String institution, String studentPersistentId);

	List<InstitutionAdvising> findByInstitutionAndAdvisorPersistentId(String institution, String advisorPersistentId);

	/** Counts only advisees the institution has a record for, the same ones the academician's own list shows. */
	@Query("select a.institution as institution, a.advisorPersistentId as advisorPersistentId, count(a) as advisees"
			+ " from InstitutionAdvising a where exists (select p.id from InstitutionPerson p"
			+ " where p.institution = a.institution and p.persistentId = a.studentPersistentId)"
			+ " group by a.institution, a.advisorPersistentId")
	List<AdvisorLoad> countAdviseesByAdvisor();

	interface AdvisorLoad {
		String getInstitution();

		String getAdvisorPersistentId();

		long getAdvisees();
	}

	boolean existsByInstitutionAndStudentPersistentIdAndAdvisorPersistentId(String institution,
			String studentPersistentId, String advisorPersistentId);
}
