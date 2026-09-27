package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.entity.MeetingProposal;

public interface MeetingProposalRepository extends JpaRepository<MeetingProposal, UUID> {

	List<MeetingProposal> findByMentorshipIdOrderByCreatedAtDesc(UUID mentorshipId);

	List<MeetingProposal> findByProgramIdInOrderByCreatedAtDesc(Collection<UUID> programIds);

	boolean existsByMentorshipIdAndStatus(UUID mentorshipId, MeetingProposalStatus status);

	List<MeetingProposal> findByStatusAndMentorshipMentorIdOrderByScheduledAtAsc(
			MeetingProposalStatus status, UUID mentorId);

	List<MeetingProposal> findByStatusAndMentorshipOrganizationIdOrderByScheduledAtAsc(
			MeetingProposalStatus status, UUID organizationId);
}
