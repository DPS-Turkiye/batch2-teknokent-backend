package com.dps.teknokent.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorAvailabilityException;

public interface MentorAvailabilityExceptionRepository
		extends JpaRepository<MentorAvailabilityException, UUID> {
	List<MentorAvailabilityException> findByMentorIdOrderByStartsOnAsc(UUID mentorId);
	List<MentorAvailabilityException> findByMentorId(UUID mentorId);

	List<MentorAvailabilityException> findByMentorIdAndEndsOnGreaterThanEqualOrderByStartsOnAsc(
			UUID mentorId, LocalDate endsOn);
	boolean existsByMentorIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
			UUID mentorId, LocalDate startsOn, LocalDate endsOn);
}
