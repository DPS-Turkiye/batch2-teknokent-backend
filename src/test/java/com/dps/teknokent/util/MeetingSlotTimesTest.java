package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.AppLanguage;

class MeetingSlotTimesTest {

	private static String slotIn(Duration fromNow) {
		return LocalDateTime.now(MeetingSlotTimes.APP_ZONE).plus(fromNow)
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
	}

	@Test
	void acceptsSlotsAtLeastFourHoursAway() {
		assertThat(MeetingSlotTimes.allBookable(List.of(slotIn(Duration.ofHours(5)), slotIn(Duration.ofDays(2)))))
				.isTrue();
		assertThat(MeetingSlotTimes.allBookable(List.of())).isTrue();
	}

	@Test
	void refusesASlotSoonerThanFourHours() {
		assertThat(MeetingSlotTimes.allBookable(List.of(slotIn(Duration.ofDays(2)), slotIn(Duration.ofHours(1)))))
				.isFalse();
	}

	@Test
	void aSlotItCannotReadDoesNotBlockTheOthers() {
		assertThat(MeetingSlotTimes.allBookable(List.of("yarın öğlen", slotIn(Duration.ofHours(5))))).isTrue();
	}

	@Test
	void theEarliestBookableTimeIsFourHoursFromNow() {
		Instant before = Instant.now();

		Instant earliest = MeetingSlotTimes.earliestBookable();

		assertThat(earliest).isBetween(before.plus(Duration.ofHours(4)), Instant.now().plus(Duration.ofHours(4)));
	}

	@Test
	void readsAMachineSlotAsTurkeyTime() {
		assertThat(MeetingSlotTimes.parseMachineSlot(" 2026-11-24T14:00 "))
				.isEqualTo(Instant.parse("2026-11-24T11:00:00Z"));
	}

	@Test
	void aMissingOrMalformedMachineSlotIsNoTime() {
		assertThat(MeetingSlotTimes.parseMachineSlot(null)).isNull();
		assertThat(MeetingSlotTimes.parseMachineSlot("  ")).isNull();
		assertThat(MeetingSlotTimes.parseMachineSlot("24.11.2026 14:00")).isNull();
		assertThat(MeetingSlotTimes.parseMachineSlot("2026-11-24T25:00")).isNull();
	}

	@Test
	void writesAMachineSlotInTurkeyTimeAndDropsTheSeconds() {
		assertThat(MeetingSlotTimes.formatMachineSlot(Instant.parse("2026-11-24T11:00:59Z")))
				.isEqualTo("2026-11-24T14:00");
	}

	@Test
	void writesATimeForPeopleInTheirLanguageAndInTurkeyTime() {
		Instant noonInTurkey = Instant.parse("2026-09-23T09:00:00Z");

		assertThat(MeetingSlotTimes.formatForPeople(noonInTurkey, AppLanguage.TR))
				.isEqualTo("23 Eylül Çarşamba · 12:00");
		assertThat(MeetingSlotTimes.formatForPeople(noonInTurkey, AppLanguage.EN))
				.isEqualTo("Wednesday, 23 September · 12:00");
	}

	@Test
	void findsTheMeetingTimeInsideAMentorsDecisionMessage() {
		assertThat(MeetingSlotTimes.parseFromDecisionMessage(
				"Kabul ediyorum. Görüşme saati: 2026-11-24T14:00 (çevrim içi)"))
				.isEqualTo(Instant.parse("2026-11-24T11:00:00Z"));
	}

	@Test
	void aDecisionMessageWithoutAMeetingTimeGivesNone() {
		assertThat(MeetingSlotTimes.parseFromDecisionMessage(null)).isNull();
		assertThat(MeetingSlotTimes.parseFromDecisionMessage(" ")).isNull();
		assertThat(MeetingSlotTimes.parseFromDecisionMessage("Bu hafta uygun değilim.")).isNull();
		assertThat(MeetingSlotTimes.parseFromDecisionMessage("Görüşme saati: yarın 14:00")).isNull();
	}
}
