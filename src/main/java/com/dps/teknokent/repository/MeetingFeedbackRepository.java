package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.MeetingFeedback;

public interface MeetingFeedbackRepository extends JpaRepository<MeetingFeedback, UUID> {

	Optional<MeetingFeedback> findByMeetingIdAndAuthorId(UUID meetingId, UUID authorId);

	List<MeetingFeedback> findByMeetingIdInAndAuthorRole(Collection<UUID> meetingIds, UserRole role);
}
