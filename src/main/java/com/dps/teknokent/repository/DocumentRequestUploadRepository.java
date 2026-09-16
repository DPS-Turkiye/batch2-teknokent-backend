package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.DocumentRequestUpload;

public interface DocumentRequestUploadRepository extends JpaRepository<DocumentRequestUpload, UUID> {

	List<DocumentRequestUpload> findByItemIdOrderByCreatedAtAsc(UUID itemId);

	List<DocumentRequestUpload> findByItemIdInOrderByCreatedAtAsc(List<UUID> itemIds);

	@Query("""
			SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END
			FROM DocumentRequestUpload u
			WHERE u.storedDocument.id = :documentId
			AND (
				u.item.createdBy.id = :mentorId
				OR (u.item.mentorshipRequest IS NOT NULL AND u.item.mentorshipRequest.mentor.id = :mentorId)
				OR (u.item.meeting IS NOT NULL AND u.item.meeting.mentor.id = :mentorId)
				OR (u.item.mentorship IS NOT NULL AND u.item.mentorship.mentor.id = :mentorId)
			)
			""")
	boolean existsVisibleToMentor(@Param("documentId") UUID documentId, @Param("mentorId") UUID mentorId);
}
