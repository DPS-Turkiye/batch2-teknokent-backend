package com.dps.teknokent.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.entity.StoredDocument;

import jakarta.persistence.EntityManager;

/** The database half of removing stored files, shared by the account and startup deletions. */
final class StoredDocumentRows {

	/** Files about a person: they go with that person, and stay when only their startup goes. */
	static final Set<DocumentKind> PERSONAL_KINDS = EnumSet.of(
			DocumentKind.CV, DocumentKind.AVATAR, DocumentKind.PROFILE_PDF, DocumentKind.PEER_EVIDENCE);
	/** Files that belong to a program, whoever uploaded them. */
	static final Set<DocumentKind> PROGRAM_KINDS = EnumSet.of(
			DocumentKind.SYLLABUS, DocumentKind.WEEKLY_PROGRAM);

	private StoredDocumentRows() {
	}

	/**
	 * Removes the rows of {@code going} and what hangs on them, and returns their storage keys, to be removed after
	 * the commit.
	 */
	static List<String> remove(EntityManager entityManager, List<StoredDocument> going) {
		if (going.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = going.stream().map(StoredDocument::getId).toList();
		run(entityManager, "delete from StoredDocumentShare s where s.documentId in :ids", ids);
		run(entityManager, "delete from DocumentRequestUpload u where u.storedDocument.id in :ids", ids);
		run(entityManager,
				"update ActionItem a set a.submissionDocumentId = null where a.submissionDocumentId in :ids", ids);
		run(entityManager, "delete from StoredDocument d where d.id in :ids", ids);
		return going.stream().map(StoredDocument::getS3Key).toList();
	}

	private static void run(EntityManager entityManager, String jpql, List<UUID> ids) {
		entityManager.createQuery(jpql).setParameter("ids", ids).executeUpdate();
	}
}
