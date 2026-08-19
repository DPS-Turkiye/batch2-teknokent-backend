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

	long countByStatusAndScheduledAtAfter(MeetingStatus status, Instant threshold);
	long countByOrganizationIdAndStatusAndScheduledAtAfter(UUID organizationId, MeetingStatus status, Instant threshold);

	Optional<Meeting> findByMentorshipRequestId(UUID mentorshipRequestId);

	boolean existsByMentorIdAndScheduledAtAndStatus(UUID mentorId, Instant scheduledAt, MeetingStatus status);
}
