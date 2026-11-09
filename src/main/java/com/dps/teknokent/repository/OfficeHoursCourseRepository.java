package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.OfficeHoursCourse;

public interface OfficeHoursCourseRepository extends JpaRepository<OfficeHoursCourse, UUID> {

	Optional<OfficeHoursCourse> findByIssuerAndContextId(String issuer, String contextId);

	/** Adds the course unless it is already there; see {@link OfficeHoursUserRepository#insertIfAbsent}. */
	@Modifying
	@Query(value = """
			INSERT INTO office_hours_course (id, issuer, context_id, title, created_at)
			VALUES (:id, :issuer, :contextId, :title, :now)
			ON CONFLICT (issuer, context_id) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("issuer") String issuer, @Param("contextId") String contextId,
			@Param("title") String title, @Param("now") Instant now);
}
