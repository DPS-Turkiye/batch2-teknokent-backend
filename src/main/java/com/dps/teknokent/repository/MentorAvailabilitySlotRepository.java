package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.entity.MentorAvailabilitySlot;

public interface MentorAvailabilitySlotRepository extends JpaRepository<MentorAvailabilitySlot, UUID> {

	List<MentorAvailabilitySlot> findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(UUID mentorId, Instant startsAt);

	boolean existsByMentorIdAndStartsAt(UUID mentorId, Instant startsAt);

	List<MentorAvailabilitySlot> findByRuleIdAndStartsAtAfter(UUID ruleId, Instant startsAt);

	/**
	 * The mentors among {@code mentorIds} with at least one slot starting in [{@code from}, {@code until}) that no
	 * meeting in {@code booked} status holds, in one query for a whole directory. A slot counts as taken the way
	 * {@code MentorProfileService} reads it: a meeting of that mentor at that exact start.
	 */
	@Query("""
			select distinct slot.mentor.id from MentorAvailabilitySlot slot
			where slot.mentor.id in :mentorIds
				and slot.startsAt >= :from and slot.startsAt < :until
				and not exists (
					select meeting.id from Meeting meeting
					where meeting.mentor = slot.mentor
						and meeting.scheduledAt = slot.startsAt
						and meeting.status = :booked)
			""")
	List<UUID> findMentorIdsWithFreeSlotBetween(@Param("mentorIds") Collection<UUID> mentorIds,
			@Param("from") Instant from, @Param("until") Instant until, @Param("booked") MeetingStatus booked);

	/**
	 * The next slots outside the weekly windows: ones the mentor added by hand, and booked ones kept after their
	 * weekly window was removed. Callers drop the booked ones.
	 */
	List<MentorAvailabilitySlot> findTop20ByMentorIdAndRuleIsNullAndStartsAtAfterOrderByStartsAtAsc(
			UUID mentorId, Instant startsAt);
}
