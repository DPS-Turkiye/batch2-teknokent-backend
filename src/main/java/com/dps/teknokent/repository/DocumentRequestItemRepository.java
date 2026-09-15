package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.DocumentRequestItem;

public interface DocumentRequestItemRepository extends JpaRepository<DocumentRequestItem, UUID> {

	@EntityGraph(attributePaths = "createdBy")
	List<DocumentRequestItem> findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(UUID mentorshipRequestId);

	@EntityGraph(attributePaths = "createdBy")
	List<DocumentRequestItem> findByMeetingIdOrderBySortOrderAscCreatedAtAsc(UUID meetingId);

	@EntityGraph(attributePaths = "createdBy")
	List<DocumentRequestItem> findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(UUID mentorshipId);

	@EntityGraph(attributePaths = "createdBy")
	List<DocumentRequestItem> findByProgramIdOrderBySortOrderAscCreatedAtAsc(UUID programId);

	@EntityGraph(attributePaths = "createdBy")
	List<DocumentRequestItem> findByOrganizationIdOrderByCreatedAtDescSortOrderAsc(UUID organizationId);

	void deleteByMentorshipRequestId(UUID mentorshipRequestId);

	void deleteByMeetingId(UUID meetingId);

	void deleteByMentorshipId(UUID mentorshipId);
}
