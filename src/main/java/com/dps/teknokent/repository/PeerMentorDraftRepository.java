package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.PeerMentorDraft;

public interface PeerMentorDraftRepository extends JpaRepository<PeerMentorDraft, UUID> {

	/**
	 * Makes the student's row if it is missing, so two first saves at once (two tabs) both land on one row instead of
	 * the second failing on the primary key; the caller then writes the payload into it. PostgreSQL only, like the
	 * office-hours inserts: H2 has no ON CONFLICT, so its test runs against PostgreSQL.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO peer_mentor_profile_draft (user_id, payload, updated_at)
			VALUES (:userId, '{}', :now)
			ON CONFLICT (user_id) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("userId") UUID userId, @Param("now") Instant now);

	/** A bulk delete: no row is fine, so a publish racing a second publish or a draft delete never fails on it. */
	@Modifying
	@Query("delete from PeerMentorDraft draft where draft.userId = :userId")
	int deleteByUserId(@Param("userId") UUID userId);
}
