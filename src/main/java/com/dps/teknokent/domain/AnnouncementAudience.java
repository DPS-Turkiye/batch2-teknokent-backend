package com.dps.teknokent.domain;

import java.util.Set;

/**
 * Who a coordinator's program announcement is for. Participants are the program's teams; in a program of
 * individual mentees (İŞKUR) each participant is a team of one, so the same value covers both.
 */
public enum AnnouncementAudience {
	EVERYONE,
	MENTORS,
	PARTICIPANTS;

	/** The audiences an accepted mentor of the program reads. */
	public static final Set<AnnouncementAudience> FOR_MENTORS = Set.of(EVERYONE, MENTORS);

	/** The audiences a member of one of the program's teams reads. */
	public static final Set<AnnouncementAudience> FOR_PARTICIPANTS = Set.of(EVERYONE, PARTICIPANTS);

	public boolean reachesMentors() {
		return FOR_MENTORS.contains(this);
	}

	public boolean reachesParticipants() {
		return FOR_PARTICIPANTS.contains(this);
	}
}
