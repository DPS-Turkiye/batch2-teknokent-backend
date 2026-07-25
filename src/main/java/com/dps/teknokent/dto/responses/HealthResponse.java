package com.dps.teknokent.dto.responses;

import com.dps.teknokent.domain.HealthStatus;

public record HealthResponse(HealthStatus status, String timestamp, HealthComponentsResponse components) {
}
