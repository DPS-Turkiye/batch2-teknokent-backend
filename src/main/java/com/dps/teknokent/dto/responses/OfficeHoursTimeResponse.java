package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.dps.teknokent.util.MeetingSlotTimes;

/** A time range as ISO instants with Turkey's offset ("2026-11-24T14:00:00+03:00"), so no client guesses a zone. */
public record OfficeHoursTimeResponse(OffsetDateTime startsAt, OffsetDateTime endsAt) {

	public static OfficeHoursTimeResponse of(Instant startsAt, int durationMinutes) {
		return new OfficeHoursTimeResponse(inTurkey(startsAt), inTurkey(startsAt.plusSeconds(durationMinutes * 60L)));
	}

	private static OffsetDateTime inTurkey(Instant instant) {
		return instant.atZone(MeetingSlotTimes.APP_ZONE).toOffsetDateTime();
	}
}
