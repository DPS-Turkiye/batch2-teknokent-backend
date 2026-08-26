package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MeetingNote;

public interface MeetingNoteRepository extends JpaRepository<MeetingNote, UUID> {

	Optional<MeetingNote> findByMeetingId(UUID meetingId);
}
