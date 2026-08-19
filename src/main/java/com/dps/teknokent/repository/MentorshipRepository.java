package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.entity.Mentorship;

public interface MentorshipRepository extends JpaRepository<Mentorship, UUID> {

	List<Mentorship> findAllByOrderByCreatedAtDesc();

	long countByStatus(MentorshipStatus status);
	long countByOrganizationIdAndStatus(UUID organizationId, MentorshipStatus status);
}
