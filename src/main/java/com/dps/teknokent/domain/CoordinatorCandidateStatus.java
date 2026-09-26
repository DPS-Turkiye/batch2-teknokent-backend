package com.dps.teknokent.domain;

/** What adding an email as a coordinator would do, so the admin sees it before saving. */
public enum CoordinatorCandidateStatus {
	/** Nobody has this email: a new coordinator account is created. */
	NEW,
	/** A mentor with no ongoing mentorship, or a mentee in no team: their role becomes coordinator. */
	PROMOTABLE,
	/** A coordinator the admin deactivated: they are let back in. */
	REACTIVATABLE,
	ALREADY_COORDINATOR,
	ADMIN,
	/** A mentee who belongs to a startup team; moving them would orphan the team's work. */
	TEAM_MEMBER,
	/** A mentor still in a planned or active mentorship. */
	MENTOR_IN_MENTORSHIP
}
