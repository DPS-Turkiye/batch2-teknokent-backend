package com.dps.teknokent.dto.requests;

import java.time.Instant;

/**
 * The student's answer to the academician's other times: take one of them, or withdraw the request.
 *
 * @param time the offered time taken; needed when {@code accept} is true
 */
public record AnswerOfficeHoursAlternativeRequest(boolean accept, Instant time) {
}
