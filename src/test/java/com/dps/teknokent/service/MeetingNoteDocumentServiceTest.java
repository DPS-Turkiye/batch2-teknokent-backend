package com.dps.teknokent.service;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingRoomEventType;
import com.dps.teknokent.dto.requests.MeetingNoteDocumentMessage;
import com.dps.teknokent.dto.responses.MeetingNoteDocumentResponse;
import com.dps.teknokent.dto.responses.MeetingRoomEvent;
import com.dps.teknokent.exception.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingNoteDocumentServiceTest {

	@Mock
	private MeetingRoomAccessService meetingRoomAccessService;
	@Mock
	private MeetingNoteDocumentStore documentStore;
	@Mock
	private MeetingRealtimePublisher realtimePublisher;

	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");

	private MeetingNoteDocumentService service() {
		return new MeetingNoteDocumentService(meetingRoomAccessService, documentStore, realtimePublisher);
	}

	@Test
	void returnsTheLiveStateWithoutHandingOutSeedRights() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		when(documentStore.findState(meetingId)).thenReturn(Optional.of("AQID"));

		MeetingNoteDocumentResponse response = service().findDocument(meetingId, organizationId);

		assertThat(response.state()).isEqualTo("AQID");
		assertThat(response.seedFromSnapshot()).isFalse();
		verify(documentStore, never()).claimSeed(meetingId);
	}

	@Test
	void letsOnlyTheFirstClientSeedAnEmptyDocument() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		when(documentStore.findState(meetingId)).thenReturn(Optional.empty());
		when(documentStore.claimSeed(meetingId)).thenReturn(true, false);

		assertThat(service().findDocument(meetingId, organizationId).seedFromSnapshot()).isTrue();
		assertThat(service().findDocument(meetingId, organizationId).seedFromSnapshot()).isFalse();
	}

	@Test
	void hidesDocumentsThatBelongToAnotherOrganization() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(false);

		assertThatThrownBy(() -> service().findDocument(meetingId, organizationId))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void relaysIncrementalUpdatesToTheRoomWithoutTouchingRedis() {
		service().applyUpdate(meetingId, new MeetingNoteDocumentMessage("AQID", false));

		ArgumentCaptor<MeetingRoomEvent> event = ArgumentCaptor.forClass(MeetingRoomEvent.class);
		verify(realtimePublisher).publish(eq(meetingId), event.capture());
		assertThat(event.getValue().type()).isEqualTo(MeetingRoomEventType.DOCUMENT_UPDATED);
		assertThat(event.getValue().update()).isEqualTo("AQID");
		verify(documentStore, never()).saveState(any(), anyString());
	}

	@Test
	void storesFullStateSnapshotsWithoutRelayingThemAgain() {
		service().applyUpdate(meetingId, new MeetingNoteDocumentMessage("AQIDBAU", true));

		verify(documentStore).saveState(meetingId, "AQIDBAU");
		verify(realtimePublisher, never()).publish(any(), any());
	}
}
