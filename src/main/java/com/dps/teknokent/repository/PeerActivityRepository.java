package com.dps.teknokent.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.PeerActivity;

public interface PeerActivityRepository extends JpaRepository<PeerActivity, UUID> {

	List<PeerActivity> findByMentorIdAndActivityDateBetweenOrderByActivityDateAscCreatedAtAsc(UUID mentorId,
			LocalDate from, LocalDate to);

	Optional<PeerActivity> findByIdAndMentorId(UUID id, UUID mentorId);
}
