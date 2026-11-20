package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record FounderChangeResponse(
		UUID organizationId,
		@Nullable UUID previousFounderId,
		UUID founderId,
		Instant changedAt) {
}
