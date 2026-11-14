package com.dps.teknokent.domain;

/** One life cycle for every report level (meeting, day, week); the frontend PeerReportStatus mirrors it. */
public enum PeerReportStatus {
	DRAFT,
	IN_REVIEW,
	APPROVED,
	REVISION_REQUESTED;

	/** The mentor can still change a draft and a report sent back for revision; a sent or approved one is locked. */
	public boolean editable() {
		return this == DRAFT || this == REVISION_REQUESTED;
	}

	/** Sent counts for the day and week gates: in review or already approved. */
	public boolean sent() {
		return this == IN_REVIEW || this == APPROVED;
	}
}
