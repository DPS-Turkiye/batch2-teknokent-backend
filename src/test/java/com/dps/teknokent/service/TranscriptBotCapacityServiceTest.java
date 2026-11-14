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

	@Test
	void saysOnlyThatEveryBotIsBusyWhenNoneFreesUpWithinADay() {
		int twentyFiveHours = 25 * 60;
		recorded(meeting("a", TEN, twentyFiveHours), meeting("b", TEN, twentyFiveHours),
				meeting("c", TEN, twentyFiveHours));

		assertThatThrownBy(() -> capacity.requireSlot(meeting("d", TEN.plusSeconds(20 * MINUTE), 30)))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("3 kayıt botunun hepsi")
				.hasMessageNotContaining("En erken");
	}

	@Test
	void namesTheFirstStartThatReallyFitsNotJustTheFirstBotToLeave() {
		TranscriptBotCapacityService twoBots = new TranscriptBotCapacityService(meetingRepository, 2);
		recorded(meeting("a", TEN, 30), meeting("b", TEN, 60), meeting("c", TEN.plusSeconds(45 * MINUTE), 75));

		// After "a" leaves at 10:35, "b" and "c" still overlap; only once "b" has left at 11:05 is a bot free.
		String firstFit = MeetingSlotTimes.formatForPeople(TEN.plusSeconds(70 * MINUTE), AppLanguage.TR);
		String firstToLeave = MeetingSlotTimes.formatForPeople(TEN.plusSeconds(40 * MINUTE), AppLanguage.TR);
		assertThatThrownBy(() -> twoBots.requireSlot(meeting("d", TEN, 60)))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining(firstFit)
				.hasMessageNotContaining(firstToLeave);
	}

	@Test
	void aMeetingWithoutALengthIsCheckedAsOneHour() {
		Instant quarterToEleven = TEN.plusSeconds(45 * MINUTE);
		recorded(meeting("a", quarterToEleven, 60), meeting("b", quarterToEleven, 60),
				meeting("c", quarterToEleven, 60));
		Meeting withoutLength = meeting("d", TEN, 60);
		withoutLength.setDurationMinutes(null);

		assertThatThrownBy(() -> capacity.requireSlot(withoutLength)).isInstanceOf(ConflictException.class);
	}

	@Test
	void aGuestCopyOfARoomThatAlreadyHasItsBotBookedNeedsNoSlotOfItsOwn() {
		Meeting booked = meeting("a", TEN, 60);
		booked.setId(UUID.randomUUID());
		recorded(booked, meeting("b", TEN, 60), meeting("c", TEN, 60));

		assertThatCode(() -> capacity.requireSlot(meeting("a", TEN, 60))).doesNotThrowAnyException();
	}

	@Test
	void leavesAMeetingThatIsOverOrHasNoTimeAlone() {
		Meeting completed = meeting("a", TEN, 60);
		completed.setStatus(MeetingStatus.COMPLETED);

		capacity.requireSlot(completed);
		capacity.requireSlot(meeting("b", TEN, 60), null, 60);

		verify(meetingRepository, never())
				.findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(any(), any(), any());
	}

	@Test
	void meetingsHoursAwayOnEitherSideDoNotCount() {
		List<Meeting> recorded = List.of(meeting("a", TEN.minusSeconds(5 * 3600), 60),
				meeting("b", TEN.plusSeconds(5 * 3600), 60));

		assertThat(TranscriptBotCapacityService.peak(recorded, TEN, TEN.plusSeconds(3600), null)).isZero();
	}

	@Test
	void meetingsWithoutALinkAreEachTheirOwnRoom() {
		Meeting first = meeting("a", TEN, 60);
		first.setId(UUID.randomUUID());
		first.setMeetLink(null);
		Meeting second = meeting("b", TEN, 60);
		second.setId(UUID.randomUUID());
		second.setMeetLink("  ");

		assertThat(TranscriptBotCapacityService.peak(List.of(first, second), TEN, TEN.plusSeconds(3600), null))
				.isEqualTo(2);
	}

	@Test
	void reportsTheConfiguredNumberOfBots() {
		assertThat(capacity.capacity()).isEqualTo(3);
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
