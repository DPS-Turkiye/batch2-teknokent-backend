package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorshipRequestMessage;

public interface MentorshipRequestMessageRepository extends JpaRepository<MentorshipRequestMessage, UUID> {

	List<MentorshipRequestMessage> findByRequestIdOrderByCreatedAtAsc(UUID requestId);
}
