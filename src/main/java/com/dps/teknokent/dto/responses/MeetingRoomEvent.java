package com.dps.teknokent.dto.responses;

import java.util.List;

import com.dps.teknokent.domain.MeetingRoomEventType;

public record MeetingRoomEvent(
		MeetingRoomEventType type,
		MeetingNoteResponse note,
		List<MeetingTodoResponse> todos) {

	public static MeetingRoomEvent noteUpdated(MeetingNoteResponse note) {
		return new MeetingRoomEvent(MeetingRoomEventType.NOTE_UPDATED, note, null);
	}

	public static MeetingRoomEvent todosUpdated(List<MeetingTodoResponse> todos) {
		return new MeetingRoomEvent(MeetingRoomEventType.TODOS_UPDATED, null, todos);
	}
}
