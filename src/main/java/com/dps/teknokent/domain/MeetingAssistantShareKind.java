package com.dps.teknokent.domain;

/**
 * What a mentor publishes to a meeting's notebook: a transcript or a report, sent in the body or uploaded to object
 * storage first (the {@code _S3} kinds).
 */
public enum MeetingAssistantShareKind {
	TRANSCRIPT,
	TRANSCRIPT_S3,
	REPORT,
	REPORT_S3;

	public boolean isTranscript() {
		return this == TRANSCRIPT || this == TRANSCRIPT_S3;
	}
}
