package com.dps.teknokent.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorshipWeekCheck;

public interface MentorshipWeekCheckRepository extends JpaRepository<MentorshipWeekCheck, UUID> {

	Optional<MentorshipWeekCheck> findByMentorshipIdAndWeekStart(UUID mentorshipId, LocalDate weekStart);

	List<MentorshipWeekCheck> findByMentorshipIdInAndWeekStart(List<UUID> mentorshipIds, LocalDate weekStart);
}
