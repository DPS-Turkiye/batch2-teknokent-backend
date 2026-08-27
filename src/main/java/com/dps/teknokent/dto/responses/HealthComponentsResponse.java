package com.dps.teknokent.dto.responses;

import com.dps.teknokent.domain.HealthStatus;

public record HealthComponentsResponse(HealthStatus database, HealthStatus redis) {
}
