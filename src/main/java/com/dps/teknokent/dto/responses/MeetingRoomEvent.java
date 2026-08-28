package com.dps.teknokent.dto.responses;

import java.util.List;

import com.dps.teknokent.domain.MeetingRoomEventType;

public record MeetingRoomEvent(
		MeetingRoomEventType type,
		List<MeetingTodoResponse> todos,
		String update) {

	public static MeetingRoomEvent documentUpdated(String update) {
		return new MeetingRoomEvent(MeetingRoomEventType.DOCUMENT_UPDATED, null, update);
	}

	public static MeetingRoomEvent presenceUpdated(String update) {
		return new MeetingRoomEvent(MeetingRoomEventType.PRESENCE_UPDATED, null, update);
	}

	public static MeetingRoomEvent todosUpdated(List<MeetingTodoResponse> todos) {
		return new MeetingRoomEvent(MeetingRoomEventType.TODOS_UPDATED, todos, null);
	}
}
