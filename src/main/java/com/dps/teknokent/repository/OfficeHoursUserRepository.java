package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.OfficeHoursUser;

public interface OfficeHoursUserRepository extends JpaRepository<OfficeHoursUser, UUID> {

	Optional<OfficeHoursUser> findByIssuerAndSubject(String issuer, String subject);

	Optional<OfficeHoursUser> findByAppUserId(UUID appUserId);

	/** The Menton side of {@link #insertIfAbsent}: two first visits at once leave one row for the account. */
	@Modifying
	@Query(value = """
			INSERT INTO office_hours_user (id, app_user_id, full_name, created_at, last_launch_at)
			VALUES (:id, :appUserId, :fullName, :now, :now)
			ON CONFLICT (app_user_id) DO NOTHING
			""", nativeQuery = true)
	int insertForAccountIfAbsent(@Param("id") UUID id, @Param("appUserId") UUID appUserId,
			@Param("fullName") String fullName, @Param("now") Instant now);

	/**
	 * Adds the person unless they are already there. Two first launches at once both succeed this way, where a
	 * plain insert would fail one of them on the unique (issuer, subject).
	 */
	@Modifying
	@Query(value = """
			INSERT INTO office_hours_user (id, issuer, subject, full_name, created_at, last_launch_at)
			VALUES (:id, :issuer, :subject, :fullName, :now, :now)
			ON CONFLICT (issuer, subject) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("issuer") String issuer, @Param("subject") String subject,
			@Param("fullName") String fullName, @Param("now") Instant now);
}
