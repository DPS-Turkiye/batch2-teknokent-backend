package com.dps.teknokent.dto.responses;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.OfficeHoursRole;

/**
 * How a booked meeting was called off, as the Menton screens show it.
 *
 * @param cancelledBy which side cancelled it
 * @param reason      their reason, if they gave one
 * @param late        called off less than 24 hours before the start (FR-OH-14)
 */
public record OfficeHoursCancellationResponse(
		OfficeHoursRole cancelledBy,
		@Nullable String reason,
		Instant cancelledAt,
		boolean late) {
}
