package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorAvailabilityRule;

public interface MentorAvailabilityRuleRepository extends JpaRepository<MentorAvailabilityRule, UUID> {

	List<MentorAvailabilityRule> findByMentorIdOrderByDayOfWeekAscStartTimeAsc(UUID mentorId);

	Optional<MentorAvailabilityRule> findByIdAndMentorId(UUID id, UUID mentorId);

	boolean existsByMentorIdAndDayOfWeekAndStartTime(UUID mentorId, short dayOfWeek, java.time.LocalTime startTime);
}
