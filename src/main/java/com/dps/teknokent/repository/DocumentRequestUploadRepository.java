package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.DocumentRequestUpload;

public interface DocumentRequestUploadRepository extends JpaRepository<DocumentRequestUpload, UUID> {

	List<DocumentRequestUpload> findByItemIdOrderByCreatedAtAsc(UUID itemId);

	List<DocumentRequestUpload> findByItemIdInOrderByCreatedAtAsc(List<UUID> itemIds);
}
