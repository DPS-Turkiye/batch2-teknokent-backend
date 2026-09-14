package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.StoredDocumentShare;

public interface StoredDocumentShareRepository extends JpaRepository<StoredDocumentShare, UUID> {

	List<StoredDocumentShare> findByDocumentIdIn(Collection<UUID> documentIds);

	boolean existsByDocumentIdAndMentorId(UUID documentId, UUID mentorId);

	void deleteByDocumentIdAndMentorId(UUID documentId, UUID mentorId);

	void deleteByDocumentId(UUID documentId);
}
