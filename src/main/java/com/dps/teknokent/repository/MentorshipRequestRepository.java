package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MentorshipRequest;

public interface MentorshipRequestRepository extends JpaRepository<MentorshipRequest, UUID> {

	List<MentorshipRequest> findAllByOrderByCreatedAtDesc();

	List<MentorshipRequest> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId);

	List<MentorshipRequest> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	List<MentorshipRequest> findByMentorIdOrderByCreatedAtDesc(UUID mentorId);
}
