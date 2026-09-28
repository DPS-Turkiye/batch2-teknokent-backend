package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

@ExtendWith(MockitoExtension.class)
class TranscriptBotCapacityServiceTest {

	private static final Instant TEN = Instant.parse("2026-09-29T07:00:00Z");

	@Mock
	private MeetingRepository meetingRepository;

	private TranscriptBotCapacityService capacity;

	@BeforeEach
	void setUp() {
		capacity = new TranscriptBotCapacityService(meetingRepository, 2);
	}

	@Test
	void turnsTheBotOffWhenTwoRoomsAreAlreadyRecordedInTheSlot() {
		recorded(meeting("a", TEN, 60), meeting("b", TEN.plusSeconds(1800), 60));
		Meeting third = meeting("c", TEN.plusSeconds(900), 60);

		assertThat(capacity.releaseIfFull(third)).isTrue();
		assertThat(third.isTranscribeRequested()).isFalse();
	}

	@Test
	void keepsTheBotWhileTheSlotHasRoom() {
		recorded(meeting("a", TEN, 60));
		Meeting second = meeting("b", TEN, 60);

		assertThat(capacity.releaseIfFull(second)).isFalse();
		assertThat(second.isTranscribeRequested()).isTrue();
	}

	@Test
	void countsGuestCopiesOfOneRoomOnce() {
		List<Meeting> recorded = List.of(meeting("a", TEN, 60), meeting("a", TEN, 60), meeting("b", TEN, 60));

		assertThat(TranscriptBotCapacityService.peak(recorded, TEN, TEN.plusSeconds(3600), null)).isEqualTo(2);
	}

	@Test
	void backToBackMeetingsDoNotShareTheBot() {
		List<Meeting> recorded = List.of(meeting("a", TEN, 60), meeting("b", TEN.plusSeconds(3600), 60));

		assertThat(TranscriptBotCapacityService.peak(recorded, TEN, TEN.plusSeconds(7200), null)).isEqualTo(1);
	}

	@Test
	void meetingsThatNeverOverlapEachOtherLeaveRoomForAThird() {
		recorded(meeting("a", TEN, 60), meeting("b", TEN.plusSeconds(3600), 60));
		Meeting spanning = meeting("c", TEN.plusSeconds(1800), 60);

		assertThat(capacity.releaseIfFull(spanning)).isFalse();
	}

	@Test
	void doesNotCountTheMeetingItselfWhenItIsEditedAgain() {
		Meeting existing = meeting("a", TEN, 60);
		existing.setId(UUID.randomUUID());
		recorded(existing, meeting("b", TEN, 60));

		assertThat(capacity.releaseIfFull(existing)).isFalse();
	}

	@Test
	void leavesMeetingsWithoutTheBotOrAlreadyJoinedAlone() {
		Meeting unrecorded = meeting("a", TEN, 60);
		unrecorded.setTranscribeRequested(false);
		Meeting joined = meeting("b", TEN, 60);
		joined.setBotDispatchedAt(TEN);

		assertThat(capacity.releaseIfFull(unrecorded)).isFalse();
		assertThat(capacity.releaseIfFull(joined)).isFalse();
		verify(meetingRepository, never())
				.findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(any(), any(), any());
	}

	private void recorded(Meeting... meetings) {
		when(meetingRepository.findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(
				eq(MeetingStatus.SCHEDULED), any(), any())).thenReturn(List.of(meetings));
	}

	private static Meeting meeting(String room, Instant at, int minutes) {
		Meeting meeting = new Meeting();
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(minutes);
		meeting.setMeetLink("https://meet.google.com/" + room);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		meeting.setTranscribeRequested(true);
		return meeting;
	}
}
