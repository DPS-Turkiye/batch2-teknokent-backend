package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorAvailabilitySlot;

public interface MentorAvailabilitySlotRepository extends JpaRepository<MentorAvailabilitySlot, UUID> {

	List<MentorAvailabilitySlot> findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(UUID mentorId, Instant startsAt);

	boolean existsByMentorIdAndStartsAt(UUID mentorId, Instant startsAt);
}
