package com.dps.teknokent.repository;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MeetingNote;

public interface MeetingNoteRepository extends JpaRepository<MeetingNote, UUID> {

	Optional<MeetingNote> findByMeetingId(UUID meetingId);

	List<MeetingNote> findByMeetingIdIn(Collection<UUID> meetingIds);
}
