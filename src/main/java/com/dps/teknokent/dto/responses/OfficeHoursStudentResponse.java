package com.dps.teknokent.dto.responses;

import java.util.UUID;

/**
 * The student behind a request, shown only to the academician it was sent to. Department and class year come from
 * ODTÜ's record and are empty for a student who came in only through Moodle.
 *
 * @param userId the student's Menton account, or their office-hours id when they have none
 */
public record OfficeHoursStudentResponse(UUID userId, String fullName, String department, Integer classYear) {
}
