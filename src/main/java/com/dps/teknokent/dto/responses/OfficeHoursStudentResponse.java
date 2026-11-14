package com.dps.teknokent.dto.responses;

import java.util.UUID;

/**
 * The student behind a request, shown only to the academician it was sent to. Department and class year come from
 * ODTÜ's record and are empty for a student who came in only through Moodle.
 *
 * @param id        the student's office-hours id, always
 * @param accountId their Menton account; empty for a student who came in only through Moodle
 */
public record OfficeHoursStudentResponse(UUID id, UUID accountId, String fullName, String department,
		Integer classYear) {
}
