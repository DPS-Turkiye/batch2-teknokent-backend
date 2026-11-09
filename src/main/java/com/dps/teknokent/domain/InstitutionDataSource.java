package com.dps.teknokent.domain;

/** How an institution_person row was last written: at that person's sign-in, by an admin import, or by the dev stub. */
public enum InstitutionDataSource {
	LOGIN,
	IMPORT,
	DEV_STUB
}
