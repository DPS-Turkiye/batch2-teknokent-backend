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

import com.dps.teknokent.entity.OfficeHoursMeeting;

public interface OfficeHoursMeetingRepository extends JpaRepository<OfficeHoursMeeting, UUID> {

	/** Meetings that still hold the academic's time. */
	List<OfficeHoursMeeting> findByInstructorIdAndCancelledAtIsNullAndScheduledAtAfter(UUID instructorId,
			Instant after);

	List<OfficeHoursMeeting> findByRequestIdIn(Collection<UUID> requestIds);

	Optional<OfficeHoursMeeting> findByRequestId(UUID requestId);

	List<OfficeHoursMeeting> findByReminderSentAtIsNullAndCancelledAtIsNullAndScheduledAtBetween(Instant from,
			Instant until);

	/** Marks the reminder sent unless another run already did; 1 means this run sends it. */
	@Modifying
	@Query("UPDATE OfficeHoursMeeting m SET m.reminderSentAt = :now WHERE m.id = :id AND m.reminderSentAt IS NULL")
	int markReminded(@Param("id") UUID id, @Param("now") Instant now);
}
