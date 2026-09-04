package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

@ExtendWith(MockitoExtension.class)
class MeetingTranscriptBotDispatcherTest {

	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private AssistantService assistantService;

	private MeetingTranscriptBotDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		dispatcher = new MeetingTranscriptBotDispatcher(meetingRepository, assistantService);
	}

	@Test
	void sendsOneBotPerMeetLinkAndMarksCopiesDispatched() {
		Instant now = Instant.parse("2026-09-02T12:00:00Z");
		Meeting first = meeting("https://meet.google.com/aaa-bbbb-ccc");
		Meeting copy = meeting("https://meet.google.com/aaa-bbbb-ccc");
		when(meetingRepository.findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
				any(), any(), any())).thenReturn(List.of(first, copy));
		when(assistantService.addBot(any())).thenReturn(
				new AddBotResponse("bot-1", "google_meet", "aaa-bbbb-ccc", "joining", "Mentor Bot"));

		int sent = dispatcher.dispatchDueMeetings(now);

		assertThat(sent).isEqualTo(1);
		ArgumentCaptor<AddBotRequest> captor = ArgumentCaptor.forClass(AddBotRequest.class);
		verify(assistantService).addBot(captor.capture());
		assertThat(captor.getValue().meetingUrl()).isEqualTo("https://meet.google.com/aaa-bbbb-ccc");
		assertThat(first.getBotDispatchedAt()).isEqualTo(now);
		assertThat(copy.getBotDispatchedAt()).isEqualTo(now);
	}

	@Test
	void leavesMeetingsUndispatchedWhenTheProviderFails() {
		Instant now = Instant.parse("2026-09-02T12:00:00Z");
		Meeting meeting = meeting("https://meet.google.com/aaa-bbbb-ccc");
		when(meetingRepository.findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
				any(), any(), any())).thenReturn(List.of(meeting));
		when(assistantService.addBot(any())).thenThrow(new IllegalStateException("provider down"));

		assertThat(dispatcher.dispatchDueMeetings(now)).isZero();
		assertThat(meeting.getBotDispatchedAt()).isNull();
	}

	@Test
	void stillSendsTheBotWhenTheMeetingAlreadyStarted() {
		Instant now = Instant.parse("2026-09-02T12:20:00Z");
		Meeting meeting = meeting("https://meet.google.com/aaa-bbbb-ccc");
		meeting.setScheduledAt(Instant.parse("2026-09-02T12:00:00Z"));
		when(meetingRepository.findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
				any(), any(), any())).thenReturn(List.of(meeting));
		when(assistantService.addBot(any())).thenReturn(
				new AddBotResponse("bot-1", "google_meet", "aaa-bbbb-ccc", "joining", "Mentor Bot"));

		assertThat(dispatcher.dispatchDueMeetings(now)).isEqualTo(1);
		assertThat(meeting.getBotDispatchedAt()).isEqualTo(now);
	}

	@Test
	void skipsMeetingsThatHaveAlreadyEnded() {
		Instant now = Instant.parse("2026-09-02T13:05:00Z");
		Meeting meeting = meeting("https://meet.google.com/aaa-bbbb-ccc");
		when(meetingRepository.findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
				any(), any(), any())).thenReturn(List.of(meeting));

		assertThat(dispatcher.dispatchDueMeetings(now)).isZero();
		verify(assistantService, never()).addBot(any());
	}

	@Test
	void skipsMeetingsWithoutAMeetLink() {
		Instant now = Instant.parse("2026-09-02T12:00:00Z");
		Meeting meeting = meeting(null);
		when(meetingRepository.findByTranscribeRequestedTrueAndBotDispatchedAtIsNullAndStatusAndScheduledAtBetween(
				any(), any(), any())).thenReturn(List.of(meeting));

		assertThat(dispatcher.dispatchDueMeetings(now)).isZero();
		verify(assistantService, never()).addBot(any());
	}

	private static Meeting meeting(String meetLink) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMeetLink(meetLink);
		meeting.setTranscribeRequested(true);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		meeting.setScheduledAt(Instant.parse("2026-09-02T12:00:00Z"));
		meeting.setDurationMinutes(60);
		return meeting;
	}
}
