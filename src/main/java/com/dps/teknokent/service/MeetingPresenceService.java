package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.requests.MeetingPresenceMessage;
import com.dps.teknokent.dto.responses.MeetingRoomEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingPresenceService {

	private final MeetingRealtimePublisher realtimePublisher;

	public void broadcast(UUID meetingId, MeetingPresenceMessage message) {
		realtimePublisher.publish(meetingId, MeetingRoomEvent.presenceUpdated(message.update()));
	}
}
