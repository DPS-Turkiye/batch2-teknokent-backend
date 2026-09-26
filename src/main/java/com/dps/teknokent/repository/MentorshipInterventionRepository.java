package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorshipIntervention;

public interface MentorshipInterventionRepository extends JpaRepository<MentorshipIntervention, UUID> {

	List<MentorshipIntervention> findAllByOrderByOpenedAtDesc();

	Optional<MentorshipIntervention> findByMentorshipIdAndResolvedAtIsNull(UUID mentorshipId);
}
