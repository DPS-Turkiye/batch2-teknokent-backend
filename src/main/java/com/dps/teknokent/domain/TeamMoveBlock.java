package com.dps.teknokent.domain;

/** Why a person cannot be moved to another team; the move screen explains it instead of offering targets. */
public enum TeamMoveBlock {
	/** Only approved accounts are moved; a pending one is decided in "Onaylar" first. */
	NOT_APPROVED,
	/** Mentors, coordinators, admins and academicians are not team members. */
	NOT_A_TEAM_MEMBER,
	/** No startup to move out of (none at all, or an ODTÜ personal team). */
	NO_TEAM,
	/** The founder leads a team with other members in it: make one of them the founder first. */
	FOUNDER
}
