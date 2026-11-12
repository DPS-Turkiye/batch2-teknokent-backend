package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.InstitutionPerson;

public interface InstitutionPersonRepository extends JpaRepository<InstitutionPerson, UUID> {

	Optional<InstitutionPerson> findByInstitutionAndPersistentId(String institution, String persistentId);

	Optional<InstitutionPerson> findFirstByUserIdOrderBySyncedAtDesc(UUID userId);

	List<InstitutionPerson> findByUserIdIn(Collection<UUID> userIds);

	List<InstitutionPerson> findByAcademicTrueOrderByFullNameAsc();

	List<InstitutionPerson> findByInstitutionAndPersistentIdInOrderByFullNameAsc(String institution,
			Collection<String> persistentIds);
}
