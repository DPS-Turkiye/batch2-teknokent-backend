package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

	// Guarded updates: the dispatcher calls Vexa with no transaction open, so the rows may have changed meanwhile.
	@Transactional
	@Modifying
	@Query("""
			update Meeting m set m.botDispatchedAt = :at, m.updatedAt = :at
			where m.id in :ids and m.botDispatchedAt is null""")
	int markBotDispatched(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

	@Transactional
	@Modifying
	@Query("""
			update Meeting m set m.botDispatchedAt = :at, m.updatedAt = :at,
			m.status = com.dps.teknokent.domain.MeetingStatus.COMPLETED
			where m.id in :ids and m.status = com.dps.teknokent.domain.MeetingStatus.SCHEDULED""")
	int markEndedAtProvider(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

	List<Meeting> findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(
			MeetingStatus status, Instant from, Instant to);

	List<Meeting> findByOrganizationIdInOrderByScheduledAtDesc(Collection<UUID> organizationIds);

	List<Meeting> findByStatusAndReminderSentAtIsNullAndScheduledAtBetween(
			MeetingStatus status, Instant from, Instant until);
}
