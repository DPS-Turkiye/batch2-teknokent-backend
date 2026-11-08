package com.dps.teknokent.dto.responses;

import java.util.UUID;

import com.dps.teknokent.domain.StudyLevel;
import com.dps.teknokent.entity.InstitutionPerson;

/** A person from the institution's records, e.g. one of an academician's advisees; {@code userId} null until they sign in. */
public record InstitutionPersonSummaryResponse(
		UUID userId,
		String fullName,
		String departmentName,
		Integer classYear,
		StudyLevel programLevel) {

	public static InstitutionPersonSummaryResponse of(InstitutionPerson person) {
		return new InstitutionPersonSummaryResponse(person.getUser() == null ? null : person.getUser().getId(),
				person.getFullName(), person.getDepartmentName(), person.getClassYear(), person.getProgramLevel());
	}
}
