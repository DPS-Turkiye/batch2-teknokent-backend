package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.PeerDocumentVisibility;
import com.dps.teknokent.entity.PeerMentorDocument;

public interface PeerMentorDocumentRepository extends JpaRepository<PeerMentorDocument, UUID> {

	List<PeerMentorDocument> findByUserIdOrderByCreatedAtAsc(UUID userId);

	List<PeerMentorDocument> findByUserIdAndVisibilityOrderByCreatedAtAsc(UUID userId, PeerDocumentVisibility visibility);

	long countByUserId(UUID userId);
}
