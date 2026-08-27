package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import com.dps.teknokent.domain.MeetingRoomDestinations;
import com.dps.teknokent.dto.requests.MeetingNoteDocumentMessage;
import com.dps.teknokent.service.MeetingNoteDocumentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class MeetingNoteDocumentController {

	private final MeetingNoteDocumentService meetingNoteDocumentService;

	@MessageMapping(MeetingRoomDestinations.DOCUMENT_MAPPING)
	public void onDocumentUpdate(
			@DestinationVariable UUID meetingId,
			@Valid @Payload MeetingNoteDocumentMessage message) {
		meetingNoteDocumentService.applyUpdate(meetingId, message);
	}
}
