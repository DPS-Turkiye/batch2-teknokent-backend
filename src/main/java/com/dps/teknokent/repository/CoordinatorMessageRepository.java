package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.CoordinatorMessage;

public interface CoordinatorMessageRepository extends JpaRepository<CoordinatorMessage, UUID> {
	List<CoordinatorMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
