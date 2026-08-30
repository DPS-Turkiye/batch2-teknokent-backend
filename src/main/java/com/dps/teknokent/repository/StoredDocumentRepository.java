package com.dps.teknokent.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.StoredDocument;

public interface StoredDocumentRepository extends JpaRepository<StoredDocument, UUID> {
}
