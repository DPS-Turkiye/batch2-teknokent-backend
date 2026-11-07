package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.OfficeHoursCourse;

public interface OfficeHoursCourseRepository extends JpaRepository<OfficeHoursCourse, UUID> {

	Optional<OfficeHoursCourse> findByIssuerAndContextId(String issuer, String contextId);
}
