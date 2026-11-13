package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.util.List;

import com.dps.teknokent.domain.OfficeHoursRequestAction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The academician's answer from Menton: accept one of the student's times, decline, offer up to three other times,
 * or ask a question (course requests only).
 *
 * @param time    the student's time being accepted (ACCEPT)
 * @param times   the other times offered (PROPOSE_TIMES)
 * @param message the reason, the note with the offered times, or the question
 */
public record AnswerOfficeHoursRequest(
		@NotNull OfficeHoursRequestAction action,
		Instant time,
		@Size(max = 3) List<@NotNull Instant> times,
		@Size(max = 2000) String message) {
}
