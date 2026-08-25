package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.MeetingTodo;

public interface MeetingTodoRepository extends JpaRepository<MeetingTodo, UUID> {

	List<MeetingTodo> findByMeetingIdOrderByDisplayOrderAsc(UUID meetingId);

	Optional<MeetingTodo> findByIdAndMeetingId(UUID id, UUID meetingId);

	long countByMeetingId(UUID meetingId);
}
