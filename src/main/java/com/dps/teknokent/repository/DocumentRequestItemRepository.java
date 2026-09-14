package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.DocumentRequestItem;

public interface DocumentRequestItemRepository extends JpaRepository<DocumentRequestItem, UUID> {

	List<DocumentRequestItem> findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(UUID mentorshipRequestId);

	List<DocumentRequestItem> findByMeetingIdOrderBySortOrderAscCreatedAtAsc(UUID meetingId);

	List<DocumentRequestItem> findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(UUID mentorshipId);

	void deleteByMentorshipRequestId(UUID mentorshipRequestId);

	void deleteByMeetingId(UUID meetingId);

	void deleteByMentorshipId(UUID mentorshipId);
}
