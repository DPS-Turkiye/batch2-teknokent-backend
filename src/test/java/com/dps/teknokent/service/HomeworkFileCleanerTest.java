package com.dps.teknokent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.repository.StoredDocumentShareRepository;

@ExtendWith(MockitoExtension.class)
class HomeworkFileCleanerTest {

	private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final String KEY = "documents/odev.pdf";

	@Mock
	private StoredDocumentRepository storedDocumentRepository;
	@Mock
	private StoredDocumentShareRepository storedDocumentShareRepository;
	@Mock
	private ActionItemRepository actionItemRepository;
	@Mock
	private DocumentRequestUploadRepository documentRequestUploadRepository;
	@Mock
	private ObjectStorage objectStorage;

	@InjectMocks
	private HomeworkFileCleaner cleaner;

	@Test
	void deletesTheTeamsUploadedFileWithItsSharesAndObject() {
		StoredDocument document = stored(DocumentKind.HOMEWORK, ORG_ID);

		cleaner.deleteIfUnused(ORG_ID, document.getId());

		verify(storedDocumentShareRepository).deleteByDocumentId(document.getId());
		verify(storedDocumentRepository).delete(document);
		verify(objectStorage).delete(KEY);
	}

	@Test
	void keepsAFilePickedFromDosyalar() {
		StoredDocument deck = stored(DocumentKind.PITCH_DECK, ORG_ID);

		cleaner.deleteIfUnused(ORG_ID, deck.getId());

		verifyKept();
	}

	@Test
	void keepsAnotherTeamsFile() {
		StoredDocument document = stored(DocumentKind.HOMEWORK, UUID.randomUUID());

		cleaner.deleteIfUnused(ORG_ID, document.getId());

		verifyKept();
	}

	@Test
	void keepsAFileAnotherOdevStillUses() {
		StoredDocument document = stored(DocumentKind.HOMEWORK, ORG_ID);
		when(actionItemRepository.existsBySubmissionDocumentId(document.getId())).thenReturn(true);

		cleaner.deleteIfUnused(ORG_ID, document.getId());

		verifyKept();
	}

	@Test
	void keepsAFileAMentorsDocumentRequestHolds() {
		StoredDocument document = stored(DocumentKind.HOMEWORK, ORG_ID);
		when(documentRequestUploadRepository.existsByStoredDocumentId(document.getId())).thenReturn(true);

		cleaner.deleteIfUnused(ORG_ID, document.getId());

		verifyKept();
	}

	@Test
	void deletesTheStoredObjectOnlyAfterCommitAndSwallowsAStorageFailure() {
		StoredDocument document = stored(DocumentKind.HOMEWORK, ORG_ID);
		TransactionSynchronizationManager.initSynchronization();
		try {
			cleaner.deleteIfUnused(ORG_ID, document.getId());
			verify(storedDocumentRepository).delete(document);
			verify(objectStorage, never()).delete(any());

			doThrow(new IllegalStateException("S3 down")).when(objectStorage).delete(KEY);
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

			verify(objectStorage).delete(KEY);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private void verifyKept() {
		verify(storedDocumentRepository, never()).delete(any());
		verify(objectStorage, never()).delete(any());
	}

	private StoredDocument stored(DocumentKind kind, UUID organizationId) {
		StoredDocument document = new StoredDocument();
		document.setId(UUID.randomUUID());
		document.setKind(kind);
		document.setOrganizationId(organizationId);
		document.setS3Key(KEY);
		when(storedDocumentRepository.findById(document.getId())).thenReturn(Optional.of(document));
		return document;
	}
}
