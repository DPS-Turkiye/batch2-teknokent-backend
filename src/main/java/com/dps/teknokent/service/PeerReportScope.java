package com.dps.teknokent.service;

import java.util.UUID;

import com.dps.teknokent.entity.PeerMeetingReport;

/**
 * Whose eyes a report is assembled for. The mentor sees all of their own work, drafts included. A reviewer sees only
 * what the mentor has sent, and only from the report's own program: a mentor's draft stays theirs, and a mentor in two
 * programs never shows one program's mentees to the other's coordinator.
 */
record PeerReportScope(boolean sentOnly, UUID programId) {

	static final PeerReportScope MENTOR = new PeerReportScope(false, null);

	static PeerReportScope reviewer(UUID programId) {
		return new PeerReportScope(true, programId);
	}

	boolean shows(PeerMeetingReport report) {
		return !sentOnly || PeerMeetingReportService.sent(report);
	}

	/** Whether a row of this program belongs to the view; the mentor's view has no program bound. */
	boolean inProgram(UUID rowProgramId) {
		return programId == null || programId.equals(rowProgramId);
	}
}
