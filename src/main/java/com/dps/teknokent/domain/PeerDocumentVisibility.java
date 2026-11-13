package com.dps.teknokent.domain;

/**
 * Who besides the peer mentor sees a document. Their program's coordinators (and admins) always do, to verify it;
 * {@link #PROFILE} also shows it to the students of that program on the mentor's profile.
 */
public enum PeerDocumentVisibility {
	VERIFICATION_ONLY,
	PROFILE
}
