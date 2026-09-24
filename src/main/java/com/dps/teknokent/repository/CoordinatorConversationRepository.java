package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.CoordinatorConversation;

public interface CoordinatorConversationRepository extends JpaRepository<CoordinatorConversation, UUID> {
	List<CoordinatorConversation> findByProgramIdIn(Collection<UUID> programIds);

	List<CoordinatorConversation> findByOrganizationId(UUID organizationId);

	List<CoordinatorConversation> findByMentorId(UUID mentorId);

	Optional<CoordinatorConversation> findByProgramIdAndOrganizationId(UUID programId, UUID organizationId);

	Optional<CoordinatorConversation> findByProgramIdAndMentorId(UUID programId, UUID mentorId);
}
