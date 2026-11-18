package com.dps.teknokent.domain;

/** Which session of a program week an attendance row or a QR code belongs to. */
public enum AttendanceSessionKind {
	/** The week's lesson: the Eğitim / Konuşmacı session on the week's first day. */
	LESSON,
	WORKSHOP,
	/** A weekly self-check-in from before attendance was taken per session; kept, never written any more. */
	WEEK
}
