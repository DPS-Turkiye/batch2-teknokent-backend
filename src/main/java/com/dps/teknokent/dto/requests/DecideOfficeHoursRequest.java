package com.dps.teknokent.dto.requests;

import java.util.List;

import com.dps.teknokent.domain.OfficeHoursRequestStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The academic's answer: ACCEPTED (at {@code slot}, one of the student's times), DECLINED, MORE_INFO_REQUESTED
 * (the question in {@code message}) or COUNTER_PROPOSED (up to three {@code proposedSlots}).
 */
public record DecideOfficeHoursRequest(
		@NotNull OfficeHoursRequestStatus status,
		@Size(max = 2000) String message,
		@Pattern(regexp = SlotFormat.PATTERN) String slot,
		@Size(max = 3) List<@Pattern(regexp = SlotFormat.PATTERN) String> proposedSlots) {
}
