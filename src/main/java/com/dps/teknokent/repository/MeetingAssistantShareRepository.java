package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MeetingAssistantShare;

public interface MeetingAssistantShareRepository extends JpaRepository<MeetingAssistantShare, UUID> {

	Optional<MeetingAssistantShare> findByMeetingId(UUID meetingId);
}
