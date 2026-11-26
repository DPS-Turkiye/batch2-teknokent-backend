package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.CoordinatorConversation;

public interface CoordinatorConversationRepository extends JpaRepository<CoordinatorConversation, UUID> {
	List<CoordinatorConversation> findByProgramIdIn(Collection<UUID> programIds);

	List<CoordinatorConversation> findByOrganizationId(UUID organizationId);

	List<CoordinatorConversation> findByMentorId(UUID mentorId);

	Optional<CoordinatorConversation> findByProgramIdAndOrganizationId(UUID programId, UUID organizationId);

	Optional<CoordinatorConversation> findByProgramIdAndMentorId(UUID programId, UUID mentorId);

	List<CoordinatorConversation> findByLastMessageAtAfter(Instant since);

	// Bulk updates: they win once even if two runs meet, and a conversation saved at the same moment keeps them.
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update CoordinatorConversation c set c.coordinatorMailedAt = :at
			where c.id = :id and (c.coordinatorMailedAt is null or c.coordinatorMailedAt < :newest)
			""")
	int claimCoordinatorMail(@Param("id") UUID id, @Param("newest") Instant newest, @Param("at") Instant at);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update CoordinatorConversation c set c.counterpartMailedAt = :at
			where c.id = :id and (c.counterpartMailedAt is null or c.counterpartMailedAt < :newest)
			""")
	int claimCounterpartMail(@Param("id") UUID id, @Param("newest") Instant newest, @Param("at") Instant at);
}
