package com.dps.teknokent.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.requests.MeetingNoteDocumentMessage;
import com.dps.teknokent.dto.responses.MeetingNoteDocumentResponse;
import com.dps.teknokent.dto.responses.MeetingRoomEvent;
import com.dps.teknokent.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingNoteDocumentService {

	private final MeetingRoomAccessService meetingRoomAccessService;
	private final MeetingNoteDocumentStore documentStore;
	private final MeetingRealtimePublisher realtimePublisher;

	public MeetingNoteDocumentResponse findDocument(UUID meetingId, UUID organizationId) {
		if (!meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)) {
			throw new ResourceNotFoundException("Meeting not found: " + meetingId);
		}

		Optional<String> state = documentStore.findState(meetingId);
		boolean seedFromSnapshot = state.isEmpty() && documentStore.claimSeed(meetingId);
		return new MeetingNoteDocumentResponse(state.orElse(null), seedFromSnapshot);
	}

	public void applyUpdate(UUID meetingId, MeetingNoteDocumentMessage message) {
		if (message.fullState()) {
			documentStore.saveState(meetingId, message.update());
			return;
		}
		realtimePublisher.publish(meetingId, MeetingRoomEvent.documentUpdated(message.update()));
	}
}
