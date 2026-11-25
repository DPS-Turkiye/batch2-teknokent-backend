package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.entity.MentorshipRequest;

import jakarta.persistence.LockModeType;

public interface MentorshipRequestRepository extends JpaRepository<MentorshipRequest, UUID> {

	List<MentorshipRequest> findByStatusInOrderByCreatedAtDesc(Collection<MentorshipRequestStatus> statuses);

	List<MentorshipRequest> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId);

	List<MentorshipRequest> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	List<MentorshipRequest> findByMentorIdOrderByCreatedAtDesc(UUID mentorId);

	/**
	 * The requests sent to a mentor from inside one program that are in one of {@code statuses}, newest first.
	 * The rows are locked until the transaction ends, and a row whose status changed while this waited for the
	 * lock is left out, so an answer given at the same moment is not overwritten.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select r from MentorshipRequest r
			where r.mentor.id = :mentorId and r.program.id = :programId and r.status in :statuses
			order by r.createdAt desc
			""")
	List<MentorshipRequest> findByMentorAndProgramForUpdate(
			@Param("mentorId") UUID mentorId,
			@Param("programId") UUID programId,
			@Param("statuses") Collection<MentorshipRequestStatus> statuses);

	/** Open conversations where the other side wrote in (since, until] and the mentor neither read nor was mailed. */
	@Query("""
			select r.id from MentorshipRequest r
			where r.status in :statuses and exists (
				select 1 from MentorshipRequestMessage m
				where m.request = r and m.author <> r.mentor
				and m.createdAt > :since and m.createdAt <= :until
				and (r.mentorReadAt is null or m.createdAt > r.mentorReadAt)
				and (r.mentorMailedAt is null or m.createdAt > r.mentorMailedAt))
			""")
	List<UUID> findIdsWithMessagesUnreadByMentor(
			@Param("statuses") Collection<MentorshipRequestStatus> statuses,
			@Param("since") Instant since,
			@Param("until") Instant until);

	// The claims are bulk updates so that they neither touch updated_at nor are won by two runs at once.
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MentorshipRequest r set r.mentorMailedAt = :at
			where r.id = :id and exists (
				select 1 from MentorshipRequestMessage m
				where m.request = r and m.author <> r.mentor
				and m.createdAt > :since and m.createdAt <= :until
				and (r.mentorReadAt is null or m.createdAt > r.mentorReadAt)
				and (r.mentorMailedAt is null or m.createdAt > r.mentorMailedAt))
			""")
	int claimUnreadMessageMail(
			@Param("id") UUID id,
			@Param("since") Instant since,
			@Param("until") Instant until,
			@Param("at") Instant at);

	/** Unanswered requests sent in [oldest, latest], outside any program or in an active one, not reminded yet. */
	@Query("""
			select r from MentorshipRequest r left join r.program p
			where r.status = com.dps.teknokent.domain.MentorshipRequestStatus.PENDING
			and r.createdAt >= :oldest and r.createdAt <= :latest and r.answerRemindedAt is null
			and (p is null or p.status = com.dps.teknokent.domain.ProgramStatus.ACTIVE)
			order by r.createdAt
			""")
	List<MentorshipRequest> findUnansweredForMentors(@Param("oldest") Instant oldest, @Param("latest") Instant latest);

	@Query("""
			select r from MentorshipRequest r join r.program p
			where r.status = com.dps.teknokent.domain.MentorshipRequestStatus.PENDING
			and r.createdAt >= :oldest and r.createdAt <= :latest and r.coordinatorAlertedAt is null
			and p.status = com.dps.teknokent.domain.ProgramStatus.ACTIVE
			order by r.createdAt
			""")
	List<MentorshipRequest> findUnansweredForCoordinators(
			@Param("oldest") Instant oldest, @Param("latest") Instant latest);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MentorshipRequest r set r.answerRemindedAt = :at
			where r.id in :ids and r.answerRemindedAt is null
			and r.status = com.dps.teknokent.domain.MentorshipRequestStatus.PENDING
			""")
	int claimAnswerReminder(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MentorshipRequest r set r.coordinatorAlertedAt = :at
			where r.id in :ids and r.coordinatorAlertedAt is null
			and r.status = com.dps.teknokent.domain.MentorshipRequestStatus.PENDING
			""")
	int claimCoordinatorAlert(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

	Optional<MentorshipRequest> findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
			UUID mentorId,
			UUID organizationId,
			MentorshipRequestStatus status);

	boolean existsByMentorIdAndOrganizationId(UUID mentorId, UUID organizationId);

	boolean existsByMentorIdAndOrganizationIdAndStatusIn(
			UUID mentorId,
			UUID organizationId,
			Collection<MentorshipRequestStatus> statuses);

	List<MentorshipRequest> findByOrganizationIdInAndStatus(
			Collection<UUID> organizationIds,
			MentorshipRequestStatus status);

	List<MentorshipRequest> findByMentorIdInAndStatus(Collection<UUID> mentorIds, MentorshipRequestStatus status);
}
