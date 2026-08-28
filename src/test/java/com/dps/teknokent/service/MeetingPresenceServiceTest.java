package com.dps.teknokent.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingRoomEventType;
import com.dps.teknokent.dto.requests.MeetingPresenceMessage;
import com.dps.teknokent.dto.responses.MeetingRoomEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MeetingPresenceServiceTest {

	@Mock
	private MeetingRealtimePublisher realtimePublisher;

	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");

	@Test
	void relaysPresenceUpdatesToTheRoom() {
		new MeetingPresenceService(realtimePublisher)
				.broadcast(meetingId, new MeetingPresenceMessage("AQID"));

		ArgumentCaptor<MeetingRoomEvent> event = ArgumentCaptor.forClass(MeetingRoomEvent.class);
		verify(realtimePublisher).publish(eq(meetingId), event.capture());
		assertThat(event.getValue().type()).isEqualTo(MeetingRoomEventType.PRESENCE_UPDATED);
		assertThat(event.getValue().update()).isEqualTo("AQID");
		assertThat(event.getValue().todos()).isNull();
	}
}
