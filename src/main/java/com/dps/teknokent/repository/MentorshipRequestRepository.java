package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

	List<MentorshipRequest> findByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
			UUID mentorId,
			UUID organizationId,
			MentorshipRequestStatus status);

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
}
