package com.dps.teknokent.domain;

/** Where a permission came from, so a badge or an ODTÜ record can take back only what it gave. */
public enum PermissionSource {
	LEGACY_ROLE,
	REGISTRATION,
	INSTITUTION,
	BADGE,
	ADMIN
}
