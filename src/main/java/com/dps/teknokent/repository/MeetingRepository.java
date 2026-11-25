package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.entity.Meeting;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

	// The request is read for every meeting to tell which program it was held in (ProgramActivity).
	@EntityGraph(attributePaths = "mentorshipRequest")
	List<Meeting> findAllByOrderByScheduledAtDesc();

	List<Meeting> findByOrganizationIdOrderByScheduledAtDesc(UUID organizationId);

	List<Meeting> findByMentorIdOrderByScheduledAtDesc(UUID mentorId);

	List<Meeting> findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(UUID mentorId, UUID organizationId);

	long countByOrganizationIdAndStatusAndScheduledAtAfter(UUID organizationId, MeetingStatus status,
			Instant threshold);

	Optional<Meeting> findByMentorshipRequestId(UUID mentorshipRequestId);

	List<Meeting> findByMentorshipRequestIdIn(Collection<UUID> mentorshipRequestIds);

	/** The meetings booked on requests the user mentors, in [from, to): the peer-mentor reports' calendar. */
	List<Meeting> findByMentorshipRequest_Mentor_IdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
			UUID mentorId, Instant from, Instant to);

	/** Every meeting between one mentor and one mentee, oldest first (meeting numbers, the previous meeting). */
	List<Meeting> findByMentorshipRequest_Mentor_IdAndMentorshipRequest_Requester_IdOrderByScheduledAtAsc(
			UUID mentorId, UUID requesterId);

	boolean existsByMentorIdAndScheduledAtAndStatus(UUID mentorId, Instant scheduledAt, MeetingStatus status);

	boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

	List<Meeting> findByMeetLinkAndScheduledAt(String meetLink, Instant scheduledAt);

	List<Meeting> findByMentorIdAndScheduledAt(UUID mentorId, Instant scheduledAt);

	List<Meeting> findByMeetLinkContainingIgnoreCase(String meetLinkFragment);

	List<Meeting> findByStatusAndMeetLinkContainingIgnoreCase(MeetingStatus status, String meetLinkFragment);

	Optional<Meeting> findByMentorIdAndOrganizationIdAndScheduledAt(
			UUID mentorId, UUID organizationId, Instant scheduledAt);

	List<Meeting> findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
			MeetingStatus status, Instant from, Instant until);

	List<Meeting> findByTranscribeRequestedTrueAndBotDispatchedAtIsNotNullAndStatus(MeetingStatus status);

	/** An upcoming meeting that has no link yet. */
	interface MissingLink {
		UUID getId();

		UUID getMentorId();

		Instant getScheduledAt();
	}

	/** Planned meetings with a mentor, not asked for in person, made before {@code madeBefore}; soonest first. */
	@Query("""
			select m.id as id, mentor.id as mentorId, m.scheduledAt as scheduledAt
			from Meeting m join m.mentor mentor left join m.mentorshipRequest r
			where m.status = com.dps.teknokent.domain.MeetingStatus.SCHEDULED
			and m.scheduledAt > :now and m.createdAt <= :madeBefore
			and (m.meetLink is null or m.meetLink = '')
			and (r is null or r.meetingMode <> com.dps.teknokent.domain.MeetingMode.IN_PERSON)
			order by m.scheduledAt, m.id
			""")
	List<MissingLink> findUpcomingWithoutLink(
			@Param("now") Instant now, @Param("madeBefore") Instant madeBefore, Limit limit);

	/** Never replaces a link someone entered meanwhile. */
	@Transactional
	@Modifying
	@Query("""
			update Meeting m set m.meetLink = :link, m.updatedAt = :at
			where m.id in :ids and (m.meetLink is null or m.meetLink = '')
			and m.status = com.dps.teknokent.domain.MeetingStatus.SCHEDULED""")
	int setMissingLink(@Param("ids") Collection<UUID> ids, @Param("link") String link, @Param("at") Instant at);

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

	@EntityGraph(attributePaths = "mentorshipRequest")
	List<Meeting> findByOrganizationIdInOrderByScheduledAtDesc(Collection<UUID> organizationIds);

	/** Meetings the mentor confirmed as held, starting in [from, until). */
	@EntityGraph(attributePaths = "mentorshipRequest")
	List<Meeting> findByOrganizationIdInAndHeldConfirmedAtIsNotNullAndScheduledAtGreaterThanEqualAndScheduledAtLessThan(
			Collection<UUID> organizationIds, Instant from, Instant until);

	List<Meeting> findByStatusAndReminderSentAtIsNullAndScheduledAtBetween(
			MeetingStatus status, Instant from, Instant until);

	/** A mentor's sessions confirmed as held, for their achievement badges. */
	List<Meeting> findByMentorIdAndHeldConfirmedAtIsNotNull(UUID mentorId);

	@Query("select distinct m.mentor.id from Meeting m where m.heldConfirmedAt is not null and m.mentor is not null")
	List<UUID> findMentorIdsWithHeldMeetings();
}
