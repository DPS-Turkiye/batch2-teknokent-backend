package com.dps.teknokent.domain;

/**
 * A piece of the product that sends data to the AI provider. Each one has its own switch
 * ({@code ai.features.*}), so a deployment can turn one off without turning off the others.
 */
public enum AiFeature {
	/** Reading a CV or pitch deck into a profile. Off: the profile is filled in by hand. */
	PROFILE_PARSING,
	/** Reading a program's syllabus or weekly program PDF into phases and weeks. Off: the coordinator types them. */
	SYLLABUS,
	/** Embedding retrieval for mentor recommendations. Off: the deterministic ranking is used. */
	MATCHING,
	/** The meeting report and the transcript cleanup before it. Off: only the transcript is kept. */
	MEETING_REPORT,
	/** Suggesting who takes a leaving member's tasks. Off: the tasks go to the team lead. */
	TASK_HANDOVER
}
