package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Size;

/** Why the meeting is off; optional, as a decline reason is. */
public record CancelOfficeHoursMeetingRequest(@Size(max = 2000) String reason) {
}
