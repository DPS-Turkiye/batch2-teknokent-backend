package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.MeetingRoomDestinations;
import com.dps.teknokent.dto.responses.MeetingRoomEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingRealtimePublisher {

	private final SimpMessagingTemplate messagingTemplate;

	public void publish(UUID meetingId, MeetingRoomEvent event) {
		messagingTemplate.convertAndSend(MeetingRoomDestinations.TOPIC_PREFIX + meetingId, event);
	}
}
