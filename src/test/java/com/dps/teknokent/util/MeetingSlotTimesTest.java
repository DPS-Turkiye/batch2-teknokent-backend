package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;

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
}
