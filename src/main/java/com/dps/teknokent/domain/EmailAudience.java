package com.dps.teknokent.domain;

/** Whom an email-center mail goes to within one program. */
public enum EmailAudience {
	PARTICIPANTS,
	MENTORS,
	/** Participants and mentors. */
	EVERYONE,
	/** People a founder listed who have no account yet. */
	UNJOINED
}
