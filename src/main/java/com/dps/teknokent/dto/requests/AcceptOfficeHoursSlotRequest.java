package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The student takes one of the times the academic proposed. */
public record AcceptOfficeHoursSlotRequest(@NotBlank @Pattern(regexp = SlotFormat.PATTERN) String slot) {
}
