package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.entity.Meeting;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

	List<Meeting> findAllByOrderByScheduledAtDesc();
	List<Meeting> findByOrganizationIdOrderByScheduledAtDesc(UUID organizationId);
	List<Meeting> findByMentorIdOrderByScheduledAtDesc(UUID mentorId);
	List<Meeting> findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(UUID mentorId, UUID organizationId);

	long countByStatusAndScheduledAtAfter(MeetingStatus status, Instant threshold);
	long countByOrganizationIdAndStatusAndScheduledAtAfter(UUID organizationId, MeetingStatus status, Instant threshold);

	Optional<Meeting> findByMentorshipRequestId(UUID mentorshipRequestId);

	boolean existsByMentorIdAndScheduledAtAndStatus(UUID mentorId, Instant scheduledAt, MeetingStatus status);

	boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

	List<Meeting> findByMeetLinkAndScheduledAt(String meetLink, Instant scheduledAt);

	List<Meeting> findByMentorIdAndScheduledAt(UUID mentorId, Instant scheduledAt);

	List<Meeting> findByMeetLinkContainingIgnoreCase(String meetLinkFragment);

	Optional<Meeting> findFirstByMeetLinkAndBotDispatchedAtIsNotNull(String meetLink);

	List<Meeting> findByStatusAndMeetLinkContainingIgnoreCase(MeetingStatus status, String meetLinkFragment);

	Optional<Meeting> findByMentorIdAndOrganizationIdAndScheduledAt(
			UUID mentorId, UUID organizationId, Instant scheduledAt);

	List<Meeting> findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
			MeetingStatus status, Instant from, Instant until);

	List<Meeting> findByTranscribeRequestedTrueAndBotDispatchedAtIsNotNullAndStatus(MeetingStatus status);
}
