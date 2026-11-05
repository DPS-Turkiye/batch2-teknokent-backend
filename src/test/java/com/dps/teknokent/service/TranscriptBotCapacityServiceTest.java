package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class TranscriptBotCapacityServiceTest {

	private static final Instant TEN = Instant.parse("2026-09-29T07:00:00Z");
	private static final long MINUTE = 60;

	@Mock
	private MeetingRepository meetingRepository;

	private TranscriptBotCapacityService capacity;

	@BeforeEach
	void setUp() {
		capacity = new TranscriptBotCapacityService(meetingRepository, 3);
	}

	@Test
	void refusesAFourthRoomAndNamesTheEarliestFreeStart() {
		recorded(meeting("a", TEN, 60), meeting("b", TEN.plusSeconds(15 * MINUTE), 60),
				meeting("c", TEN.plusSeconds(30 * MINUTE), 60));
		Meeting fourth = meeting("d", TEN.plusSeconds(20 * MINUTE), 30);

		// "a" ends at 11:00, its bot leaves at 11:05, so the next bot can join at 11:05 for an 11:10 start.
		String earliest = MeetingSlotTimes.formatForPeople(TEN.plusSeconds(70 * MINUTE), AppLanguage.TR);
		assertThatThrownBy(() -> capacity.requireSlot(fourth))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining(earliest);
		assertThat(fourth.isTranscribeRequested()).isTrue();
	}

	@Test
	void letsTheThirdRoomIn() {
		recorded(meeting("a", TEN, 60), meeting("b", TEN, 60));

		assertThatCode(() -> capacity.requireSlot(meeting("c", TEN, 60))).doesNotThrowAnyException();
	}

	@Test
	void countsGuestCopiesOfOneRoomOnce() {
		List<Meeting> recorded = List.of(meeting("a", TEN, 60), meeting("a", TEN, 60), meeting("b", TEN, 60));

		assertThat(TranscriptBotCapacityService.peak(recorded, TEN, TEN.plusSeconds(3600), null)).isEqualTo(2);
	}

	@Test
	void backToBackMeetingsNeedTwoBotsForTheChangeover() {
		List<Meeting> recorded = List.of(meeting("a", TEN, 60), meeting("b", TEN.plusSeconds(3600), 60));

		assertThat(TranscriptBotCapacityService.peak(recorded, TEN, TEN.plusSeconds(7200), null)).isEqualTo(2);
	}

	@Test
	void meetingsTenMinutesApartShareOneBot() {
		List<Meeting> recorded = List.of(meeting("a", TEN, 60), meeting("b", TEN.plusSeconds(70 * MINUTE), 60));

		assertThat(TranscriptBotCapacityService.peak(recorded, TEN, TEN.plusSeconds(130 * MINUTE), null))
				.isEqualTo(1);
	}

	@Test
	void doesNotCountTheMeetingItselfWhenItIsEditedAgain() {
		Meeting existing = meeting("a", TEN, 60);
		existing.setId(UUID.randomUUID());
		recorded(existing, meeting("b", TEN, 60), meeting("c", TEN, 60));

		assertThatCode(() -> capacity.requireSlot(existing)).doesNotThrowAnyException();
	}

	@Test
	void checksTheNewTimeOfAMeetingAboutToMove() {
		Meeting moving = meeting("d", TEN.plusSeconds(4 * 3600), 60);
		moving.setId(UUID.randomUUID());
		recorded(moving, meeting("a", TEN, 60), meeting("b", TEN, 60), meeting("c", TEN, 60));

		assertThatThrownBy(() -> capacity.requireSlot(moving, TEN, 60)).isInstanceOf(ConflictException.class);
	}

	@Test
	void leavesMeetingsWithoutTheBotOrAlreadyJoinedAlone() {
		Meeting unrecorded = meeting("a", TEN, 60);
		unrecorded.setTranscribeRequested(false);
		Meeting joined = meeting("b", TEN, 60);
		joined.setBotDispatchedAt(TEN);

		capacity.requireSlot(unrecorded);
		capacity.requireSlot(joined);
		verify(meetingRepository, never())
				.findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(any(), any(), any());
	}

	@Test
	void reservesTheSlotsOfBookedBotsDueWithinTheHour() {
		Meeting sent = meeting("c", TEN, 60);
		sent.setBotDispatchedAt(TEN.minusSeconds(2 * MINUTE));
		recorded(meeting("a", TEN.plusSeconds(20 * MINUTE), 30), meeting("b", TEN.plusSeconds(40 * MINUTE), 60),
				sent);

		assertThat(capacity.reservedWithinHour(TEN)).isEqualTo(2);
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
