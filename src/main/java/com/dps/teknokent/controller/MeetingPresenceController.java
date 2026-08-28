package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import com.dps.teknokent.domain.MeetingRoomDestinations;
import com.dps.teknokent.dto.requests.MeetingPresenceMessage;
import com.dps.teknokent.service.MeetingPresenceService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class MeetingPresenceController {

	private final MeetingPresenceService meetingPresenceService;

	@MessageMapping(MeetingRoomDestinations.PRESENCE_MAPPING)
	public void onPresenceUpdate(
			@DestinationVariable UUID meetingId,
			@Valid @Payload MeetingPresenceMessage message) {
		meetingPresenceService.broadcast(meetingId, message);
	}
}
