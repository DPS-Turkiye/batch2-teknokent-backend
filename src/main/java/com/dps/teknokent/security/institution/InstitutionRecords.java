package com.dps.teknokent.security.institution;

import java.time.Instant;

import com.dps.teknokent.entity.InstitutionPerson;

public final class InstitutionRecords {

	private InstitutionRecords() {
	}

	/** Copies what the institution says onto its row; the row's user link and source are the caller's. */
	public static void apply(InstitutionIdentity identity, InstitutionPerson person, Instant now) {
		person.setKinds(identity.kinds());
		person.setFullName(identity.fullName());
		person.setEmail(identity.email());
		person.setFaculty(identity.faculty());
		person.setDepartmentCode(identity.departmentCode());
		person.setDepartmentName(identity.departmentName());
		person.setClassYear(identity.classYear());
		person.setProgramLevel(identity.programLevel());
		person.setAcademicTitle(identity.academicTitle());
		person.setIskurParticipant(identity.iskurParticipant());
		person.setActive(true);
		person.setSyncedAt(now);
	}
}
