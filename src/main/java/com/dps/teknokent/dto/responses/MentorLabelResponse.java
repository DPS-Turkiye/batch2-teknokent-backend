package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.MentorLabelColor;

public final class MentorLabelResponse {

	private MentorLabelResponse() {
	}

	public record Label(UUID id, String name, MentorLabelColor color) {
	}

	/** A label in the coordinator's list, with how many mentors carry it (for the delete confirmation). */
	public record BoardLabel(UUID id, String name, MentorLabelColor color, int mentorCount) {
	}

	/** The labels one mentor carries in the program, in the program list's order. */
	public record MentorLabels(UUID mentorId, List<UUID> labelIds) {
	}

	/**
	 * Mentor Havuzu's labels. When the program's switch is off, {@code enabled} is false and both lists are empty;
	 * {@code editable} is false also for an archived program.
	 */
	public record Board(boolean enabled, boolean editable, List<BoardLabel> labels, List<MentorLabels> mentors) {
	}

	/**
	 * The labels a mentee sees on their program's mentors. {@code programId} is null, and the lists are empty, when the
	 * mentee has no program or the program's switch is off.
	 */
	public record Directory(@Nullable UUID programId, List<Label> labels, List<MentorLabels> mentors) {
	}

	/** One program's labels on one mentor, for the mentor's profile. */
	public record ProgramLabels(UUID programId, String programName, List<Label> labels) {
	}
}
