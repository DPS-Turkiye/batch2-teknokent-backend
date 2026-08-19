package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorProfile;

public interface MentorProfileRepository extends JpaRepository<MentorProfile, UUID> {

	@EntityGraph(attributePaths = "user")
	List<MentorProfile> findAllByOrderBySortOrderAsc();

	@EntityGraph(attributePaths = "user")
	Optional<MentorProfile> findOneById(UUID id);
}
