package com.dps.teknokent.domain;

/** How an attendance row was recorded. */
public enum AttendanceMethod {
	/** The participant tapped "I attended" for the week (before QR codes). */
	SELF,
	/** The participant scanned the session's QR code. */
	QR,
	/** A coordinator marked the participant present. */
	MANUAL
}
