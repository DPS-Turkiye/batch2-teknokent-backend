package com.dps.teknokent.domain;

/** A row of a program's Mentor Havuzu, from invited to active. */
public enum MentorPoolStatus {
	ACTIVE,
	FULL,
	AWAITING_APPROVAL,
	INVITED,
	INVITE_EXPIRING,
	INVITE_EXPIRED
}
