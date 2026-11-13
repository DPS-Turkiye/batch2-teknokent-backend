package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.PeerMeetingReport;

public interface PeerMeetingReportRepository extends JpaRepository<PeerMeetingReport, UUID> {

	Optional<PeerMeetingReport> findByMeetingIdAndMenteeId(UUID meetingId, UUID menteeId);

	List<PeerMeetingReport> findByMentorIdAndMeetingIdIn(UUID mentorId, Collection<UUID> meetingIds);
}
