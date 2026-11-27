package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.repository.StoredDocumentShareRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes a file a team uploaded for an Ödev once no Ödev holds it any more ("Kaldır", or a coordinator deleting a
 * program Ödev). A file picked from Dosyalar, or one another Ödev or a mentor's document request still uses, stays.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HomeworkFileCleaner {

	private final StoredDocumentRepository storedDocumentRepository;
	private final StoredDocumentShareRepository storedDocumentShareRepository;
	private final ActionItemRepository actionItemRepository;
	private final DocumentRequestUploadRepository documentRequestUploadRepository;
	private final ObjectStorage objectStorage;

	/**
	 * Call once the Ödev rows that held the file are detached or deleted and flushed: the row goes now, the stored
	 * object once the deletion has committed.
	 */
	public void deleteIfUnused(UUID organizationId, UUID documentId) {
		storedDocumentRepository.findById(documentId)
				.filter(document -> document.getKind() == DocumentKind.HOMEWORK)
				.filter(document -> organizationId.equals(document.getOrganizationId()))
				.filter(document -> !actionItemRepository.existsBySubmissionDocumentId(documentId))
				// A mentor's document request may hold the same file (its link has no ON DELETE rule).
				.filter(document -> !documentRequestUploadRepository.existsByStoredDocumentId(documentId))
				.ifPresent(this::deleteWithItsObject);
	}

	private void deleteWithItsObject(StoredDocument document) {
		storedDocumentShareRepository.deleteByDocumentId(document.getId());
		storedDocumentRepository.delete(document);
		String s3Key = document.getS3Key();
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			deleteObject(document.getId(), s3Key);
			return;
		}
		// The object goes only once the row's deletion has committed: a storage hiccup leaves an orphaned object
		// rather than a record pointing at nothing.
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				deleteObject(document.getId(), s3Key);
			}
		});
	}

	private void deleteObject(UUID documentId, String s3Key) {
		try {
			objectStorage.delete(s3Key);
		} catch (RuntimeException error) {
			log.warn("Deleted document {} but its stored object {} remains: {}", documentId, s3Key,
					error.getMessage());
		}
	}
}
