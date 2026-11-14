package com.dps.teknokent.dto.responses;

/**
 * What an academician tells students about their office hours; title and department come from ODTÜ's record.
 *
 * @param listed whether Menton students see them in the list (off until they turn it on)
 */
public record OfficeHoursProfileResponse(boolean listed, String defaultPlace, String onlineLink, String contactEmail) {
}
