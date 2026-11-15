package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.OfficeHoursMeeting;

public interface OfficeHoursMeetingRepository extends JpaRepository<OfficeHoursMeeting, UUID> {

	/** Meetings that still hold the academic's time. */
	List<OfficeHoursMeeting> findByInstructorIdAndCancelledAtIsNullAndScheduledAtAfter(UUID instructorId,
			Instant after);

	List<OfficeHoursMeeting> findByRequestIdIn(Collection<UUID> requestIds);

	Optional<OfficeHoursMeeting> findByRequestId(UUID requestId);

	/** Live meetings starting in (from, until] that still owe either side its reminder; a cancelled one owes none. */
	@Query("""
			select m from OfficeHoursMeeting m
			where m.scheduledAt > :from and m.scheduledAt <= :until
			and m.cancelledAt is null and (m.studentRemindedAt is null or m.instructorRemindedAt is null)
			""")
	List<OfficeHoursMeeting> findReminderDue(@Param("from") Instant from, @Param("until") Instant until);

	// A side is claimed before its reminder is sent, so two servers never both send it, and released when sending
	// failed, so the next run tries again. Each runs in its own transaction: the claim must be visible to the other
	// server before the slow send starts. The claim also requires the meeting to be live, in the same update: one
	// cancelled after findReminderDue read the list is not claimed, so it gets no reminder (no check-then-act gap).

	@Transactional
	@Modifying
	@Query("""
			update OfficeHoursMeeting m set m.studentRemindedAt = :now
			where m.id = :id and m.studentRemindedAt is null and m.cancelledAt is null
			""")
	int claimStudentReminder(@Param("id") UUID id, @Param("now") Instant now);

	@Transactional
	@Modifying
	@Query("""
			update OfficeHoursMeeting m set m.instructorRemindedAt = :now
			where m.id = :id and m.instructorRemindedAt is null and m.cancelledAt is null
			""")
	int claimInstructorReminder(@Param("id") UUID id, @Param("now") Instant now);

	@Transactional
	@Modifying
	@Query("update OfficeHoursMeeting m set m.studentRemindedAt = null where m.id = :id")
	int releaseStudentReminder(@Param("id") UUID id);

	@Transactional
	@Modifying
	@Query("update OfficeHoursMeeting m set m.instructorRemindedAt = null where m.id = :id")
	int releaseInstructorReminder(@Param("id") UUID id);

	/**
	 * Cancels the meeting unless someone already did; 1 means this call cancelled it. The student and the academic
	 * may press cancel at the same moment, and only one of them may win and send the e-mail.
	 */
	@Modifying
	@Query("""
			UPDATE OfficeHoursMeeting m SET m.cancelledAt = :now, m.cancelledBy = :by, m.cancellationReason = :reason
			WHERE m.id = :id AND m.cancelledAt IS NULL
			""")
	int cancelIfLive(@Param("id") UUID id, @Param("now") Instant now, @Param("by") UUID by,
			@Param("reason") @Nullable String reason);

	List<OfficeHoursMeeting> findByInstructorIdAndCancelledAtIsNullAndScheduledAtAfterOrderByScheduledAtAsc(
			UUID instructorId,
			Instant after);
}
