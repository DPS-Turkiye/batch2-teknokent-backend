package com.dps.teknokent.dto.responses;

/** Which AI features this deployment offers, so the frontend can hide what is off and show the manual path. */
public record AiFeaturesResponse(
		boolean profileParsing,
		boolean syllabus,
		boolean matching,
		boolean meetingReport,
		boolean taskHandover) {
}
