package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.MeetingTodo;

public record MeetingTodoResponse(
		UUID id,
		UUID meetingId,
		String title,
		Boolean done,
		Integer displayOrder,
		String createdByName,
		Instant updatedAt) {

	public static MeetingTodoResponse fromEntity(MeetingTodo todo) {
		return new MeetingTodoResponse(
				todo.getId(),
				todo.getMeeting().getId(),
				todo.getTitle(),
				todo.getDone(),
				todo.getDisplayOrder(),
				todo.getCreatedBy() == null ? null : todo.getCreatedBy().getFullName(),
				todo.getUpdatedAt());
	}
}
